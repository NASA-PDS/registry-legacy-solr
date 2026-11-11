#!/usr/bin/env bash
#
# Installs a release of the Legacy Solr Registry tools (catalog, harvest and registry manager) and
# installs or upgrades the Solr registry that runs in Docker.
#
# Usage: install-release.sh [VERSION]
#
# VERSION is a release number such as 5.5.5, or latest for the newest published release. It can also be
# given in REGISTRY_LEGACY_VERSION. The script
#
#   1. downloads the catalog-legacy, harvest-legacy and registry-mgr-legacy tarballs of the GitHub release,
#      unpacks them into INSTALL_DIR and repoints the symlinks there (the previous versions stay on disk), then
#   2. upgrades the registry with the new registry manager (or installs it when no registry container exists), then
#   3. runs a few smoke queries against the Solr handlers (select, all, keyword, search, archive-filter) to check
#      that they answer; when a registry that held documents answers none, the script exits with an error.
#
# It checks its inputs before it changes anything, and refuses to touch Solr while a harvest, a catalog run
# or a document load is running (a Solr restart in the middle of a harvest makes the harvest skip products).
#
# Environment (all optional unless marked). The yes/no variables accept only yes or no; any other value is refused.
#   REGISTRY_LEGACY_VERSION  Release to install, without the leading v, or latest (required unless given as
#                            an argument)
#   INSTALL_DIR              Directory holding the unpacked releases and the symlinks (default: current directory)
#   DATA_HOME                Directory with the Solr volume (required when the registry is installed or upgraded)
#   BACKUP_HOME              Directory for the backup an upgrade takes (default: $DATA_HOME/solr-backups)
#   UPGRADE_REGISTRY         yes (default) to install or upgrade the registry; no to only install the tools
#   SKIP_BACKUP              yes to upgrade without taking a backup first (default: no)
#   RELOAD_ALL               prod or staging to run reload_all.sh of the new harvest after the upgrade, which
#                            reloads the registry in the right order (historical PDS3 documents, PDS4 documents,
#                            PDS3 documents); staging also harvests and catalogs first. no to skip it (default: no).
#                            Needs PDS4_SOLR_DOC_HOME, PDS3_SOLR_DOC_HOME and PDS3_HISTORY, and for staging also
#                            HARVEST_SOLR_CONF_HOME and PDS3_DATA_PATH
#   SMOKE_QUERIES            no to skip the smoke queries after an install or upgrade (default: yes)
#   PERSIST_ENV              yes to add missing exports for the tools and for the variables set here to
#                            PROFILE_FILE (default: no; existing exports are never changed)
#   PROFILE_FILE             Shell profile that PERSIST_ENV appends to (default: $HOME/.bash_profile)
#   DRY_RUN                  yes to print what would be done and change nothing (default: no)
#   FORCE                    yes to run although a harvest, catalog run or load is running (default: no)
#   SOLR_URL                Solr base URL for the checks (default: http://localhost:8983/solr)
#   CATALOG_LINK, HARVEST_LINK, REGISTRY_MGR_LINK
#                            Symlink names in INSTALL_DIR (default: catalog-legacy, harvest-legacy,
#                            registry-manager-legacy)
#   RELEASE_URL_BASE         Where the releases are downloaded from
#   RELEASES_LATEST_URL      The page that redirects to the newest release (used for latest)
#
# Example:
#   export INSTALL_DIR="$HOME/applications" DATA_HOME="$HOME/registry-data"
#   ./install-release.sh latest
#
# To fetch the script on a host that has no checkout of this repository:
#   curl -fLO https://raw.githubusercontent.com/NASA-PDS/registry-legacy-solr/main/scripts/install-release.sh

set -u -o pipefail

RELEASE_URL_BASE=${RELEASE_URL_BASE:-https://github.com/NASA-PDS/registry-legacy-solr/releases/download}
RELEASES_LATEST_URL=${RELEASES_LATEST_URL:-https://github.com/NASA-PDS/registry-legacy-solr/releases/latest}
INSTALL_DIR=${INSTALL_DIR:-$PWD}
CATALOG_LINK=${CATALOG_LINK:-catalog-legacy}
HARVEST_LINK=${HARVEST_LINK:-harvest-legacy}
REGISTRY_MGR_LINK=${REGISTRY_MGR_LINK:-registry-manager-legacy}
UPGRADE_REGISTRY=${UPGRADE_REGISTRY:-yes}
SKIP_BACKUP=${SKIP_BACKUP:-no}
RELOAD_ALL=${RELOAD_ALL:-no}
SMOKE_QUERIES=${SMOKE_QUERIES:-yes}
PERSIST_ENV=${PERSIST_ENV:-no}
PROFILE_FILE=${PROFILE_FILE:-$HOME/.bash_profile}
DRY_RUN=${DRY_RUN:-no}
FORCE=${FORCE:-no}
# The name that registry_legacy_installer_docker.sh gives its container
DOCKER_CONTAINER=registry-legacy
SOLR_URL=${SOLR_URL:-http://localhost:8983/solr}

VERSION=${1:-${REGISTRY_LEGACY_VERSION:-}}
VERSION=${VERSION#v}

# Commands that undo the symlink changes, filled in as the links are switched
ROLLBACK=()

# Set to 1 when a smoke query fails
SMOKE_FAILED=0

log() {
  echo "[INFO] $*"
}

warn() {
  echo "[WARN] $*" >&2
}

die() {
  echo "[ERROR] $*" >&2
  exit 1
}

is_yes() {
  [[ "$1" == "yes" ]]
}

# Runs a command, or only prints it in a dry run
run() {
  if is_yes "$DRY_RUN"; then
    echo "[DRY RUN] $*"
  else
    "$@"
  fi
}

# Downloads a URL to a file, leaving no partial file behind when it fails
fetch() {
  local url=$1
  local dest=$2
  local ok=1

  if command -v curl > /dev/null 2>&1; then
    curl --fail --silent --show-error --location --retry 3 --output "${dest}.part" "$url" && ok=0
  else
    wget --quiet --output-document="${dest}.part" "$url" && ok=0
  fi

  if [[ $ok -ne 0 ]]; then
    rm -f "${dest}.part"
    return 1
  fi
  mv "${dest}.part" "$dest"
}

# Finds the number of the newest published release by following the redirect of the latest release page
resolve_latest() {
  local final
  command -v curl > /dev/null 2>&1 || die "curl is required to find the latest release; give a release number instead"
  final=$(curl --silent --show-error --location --head --output /dev/null --write-out '%{url_effective}' "$RELEASES_LATEST_URL") \
    || die "Could not look up the latest release at $RELEASES_LATEST_URL"
  final=${final##*/}
  [[ "$final" =~ ^v[0-9]+\.[0-9]+\.[0-9]+ ]] || die "Could not read a release number from the latest release page (found '$final')"
  echo "${final#v}"
}

# Refuses a yes/no variable that holds anything but yes or no, so that a typo such as DRY_RUN=ye cannot turn
# into a real run
validate_switches() {
  local name
  for name in UPGRADE_REGISTRY SKIP_BACKUP SMOKE_QUERIES PERSIST_ENV DRY_RUN FORCE; do
    case "${!name}" in
      yes | no) ;;
      *) die "${name} must be yes or no (found '${!name}')" ;;
    esac
  done
}

# Checks everything the script needs, before anything is downloaded or changed
preflight() {
  [[ -n "$VERSION" ]] || die "Give the release as an argument or in REGISTRY_LEGACY_VERSION, for example 5.5.5 or latest"
  [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$ ]] || die "'$VERSION' is not a release number such as 5.5.5 (or latest)"

  command -v tar > /dev/null 2>&1 || die "tar is required"
  command -v pgrep > /dev/null 2>&1 || die "pgrep is required to check that no harvest, catalog run or load is running"
  command -v curl > /dev/null 2>&1 || command -v wget > /dev/null 2>&1 || die "curl or wget is required"
  command -v java > /dev/null 2>&1 || [[ -x "${JAVA_HOME:-/nonexistent}/bin/java" ]] \
    || warn "Java 17 or higher was not found (JAVA_HOME or PATH); the tools need it"

  if is_yes "$UPGRADE_REGISTRY"; then
    command -v docker > /dev/null 2>&1 || die "docker is required to install or upgrade the registry (or set UPGRADE_REGISTRY=no)"
    command -v curl > /dev/null 2>&1 || die "curl is required to install or upgrade the registry (or set UPGRADE_REGISTRY=no)"
    [[ -n "${DATA_HOME:-}" ]] || die "DATA_HOME must be set to install or upgrade the registry (or set UPGRADE_REGISTRY=no)"
  fi

  case "$RELOAD_ALL" in
    no | prod | staging) ;;
    *) die "RELOAD_ALL must be no, prod or staging (found '$RELOAD_ALL')" ;;
  esac
  if [[ "$RELOAD_ALL" != "no" ]]; then
    local name
    local needed="PDS4_SOLR_DOC_HOME PDS3_SOLR_DOC_HOME PDS3_HISTORY"
    is_yes "$UPGRADE_REGISTRY" || die "RELOAD_ALL=${RELOAD_ALL} needs UPGRADE_REGISTRY=yes"
    [[ "$RELOAD_ALL" == "staging" ]] && needed+=" HARVEST_SOLR_CONF_HOME PDS3_DATA_PATH"
    for name in $needed; do
      [[ -n "${!name:-}" ]] || die "RELOAD_ALL=${RELOAD_ALL} needs ${name}"
    done

    # reload_all.sh runs after the registry has been upgraded, so refuse inputs it would reject
    [[ -d "$PDS4_SOLR_DOC_HOME" ]] || die "PDS4_SOLR_DOC_HOME is not a directory: $PDS4_SOLR_DOC_HOME"
    [[ -d "$PDS3_SOLR_DOC_HOME" ]] || die "PDS3_SOLR_DOC_HOME is not a directory: $PDS3_SOLR_DOC_HOME"
    has_docs "$PDS3_HISTORY" || die "There are no Solr documents (*.xml) in PDS3_HISTORY: $PDS3_HISTORY"
    if [[ "$RELOAD_ALL" == "prod" ]]; then
      has_docs "$PDS4_SOLR_DOC_HOME/solr-docs" || die "RELOAD_ALL=prod loads the documents generated on staging, but there are none in $PDS4_SOLR_DOC_HOME/solr-docs"
      has_docs "$PDS3_SOLR_DOC_HOME/solr-docs" || die "RELOAD_ALL=prod loads the documents generated on staging, but there are none in $PDS3_SOLR_DOC_HOME/solr-docs"
    else
      [[ -d "$HARVEST_SOLR_CONF_HOME" ]] || die "HARVEST_SOLR_CONF_HOME is not a directory: $HARVEST_SOLR_CONF_HOME"
      [[ -d "$PDS3_DATA_PATH" ]] || die "PDS3_DATA_PATH is not a directory: $PDS3_DATA_PATH"
    fi
  fi

  if is_yes "$PERSIST_ENV"; then
    if [[ -e "$PROFILE_FILE" ]]; then
      [[ -f "$PROFILE_FILE" && -w "$PROFILE_FILE" ]] || die "PROFILE_FILE is not a writable file: $PROFILE_FILE"
    else
      [[ -d "$(dirname "$PROFILE_FILE")" && -w "$(dirname "$PROFILE_FILE")" ]] || die "PROFILE_FILE cannot be created: $PROFILE_FILE"
    fi
  fi

  # Before anything is created, so that a refused run leaves nothing behind
  check_nothing_running

  # Last, so that a refused run leaves nothing behind
  if ! is_yes "$DRY_RUN"; then
    mkdir -p "$INSTALL_DIR" || die "Cannot create INSTALL_DIR: $INSTALL_DIR"
    [[ -w "$INSTALL_DIR" ]] || die "INSTALL_DIR is not writable: $INSTALL_DIR"
  fi
}

# Succeeds if the directory holds at least one Solr document file (the check reload_all.sh makes)
has_docs() {
  local dir=$1
  [[ -d "$dir" ]] && [[ -n "$(find "$dir" -type f -name '*.xml' 2> /dev/null | head -n 1)" ]]
}

# Refuses to go on while something that talks to Solr is running. It is checked at the start and again right
# before the registry is restarted, because the downloads in between can take a while.
check_nothing_running() {
  local running
  local rc
  running=$(pgrep -fl "harvest-solr|HarvestSearchLauncher|catalog-solr|registry-mgr-solr|reload_all")
  rc=$?
  # pgrep exits with 1 when nothing matches; anything above 1 means it could not look
  [[ $rc -le 1 ]] || die "pgrep failed (exit status ${rc}), so the script cannot tell whether a harvest or load is running"
  if [[ -n "$running" ]]; then
    echo "$running" >&2
    is_yes "$FORCE" || die "The processes above are running. Wait for them, or set FORCE=yes."
    warn "FORCE=yes: going on although the processes above are running"
  fi
}

# Downloads and unpacks one release tarball into INSTALL_DIR
unpack_module() {
  local module=$1
  local name="${module}-${VERSION}"
  local tarball="${INSTALL_DIR}/${name}-bin.tar.gz"

  if [[ -d "${INSTALL_DIR}/${name}" ]]; then
    [[ -d "${INSTALL_DIR}/${name}/bin" ]] \
      || die "${INSTALL_DIR}/${name} exists but has no bin directory, so an earlier unpack may have been interrupted. Remove it and run the script again."
    log "${name} is already unpacked"
    return 0
  fi

  if [[ ! -f "$tarball" ]]; then
    log "Downloading ${name}-bin.tar.gz"
    run fetch "${RELEASE_URL_BASE}/v${VERSION}/${name}-bin.tar.gz" "$tarball" \
      || die "Could not download ${RELEASE_URL_BASE}/v${VERSION}/${name}-bin.tar.gz (has release v${VERSION} been published? See https://github.com/NASA-PDS/registry-legacy-solr/releases)"
  fi

  log "Unpacking ${name}-bin.tar.gz"
  run tar --extract --gzip --file "$tarball" --directory "$INSTALL_DIR" || die "Could not unpack $tarball"

  if ! is_yes "$DRY_RUN"; then
    [[ -d "${INSTALL_DIR}/${name}/bin" ]] || die "${INSTALL_DIR}/${name} has no bin directory after unpacking"
  fi
}

# Points a symlink in INSTALL_DIR at an unpacked release, and notes how to undo it
switch_link() {
  local module=$1
  local link=$2
  local target="${module}-${VERSION}"
  local path="${INSTALL_DIR}/${link}"
  local previous=""

  if [[ -e "$path" && ! -L "$path" ]]; then
    die "$path exists and is not a symlink. Move it away, or choose another name in ${link}."
  fi
  if [[ -L "$path" ]]; then
    previous=$(readlink "$path")
  fi

  if [[ "$previous" == "$target" || "$previous" == "${INSTALL_DIR}/${target}" ]]; then
    log "${link} already points at ${target}"
    return 0
  fi

  # -n keeps ln from creating the new link inside the directory the old one points to
  run ln -sfn "$target" "$path" || die "Could not point ${path} at ${target}"
  log "${link} -> ${target} (was: ${previous:-nothing})"
  if [[ -n "$previous" ]]; then
    ROLLBACK+=("ln -sfn '${previous}' '${path}'")
  fi
}

# Adds an export to the profile unless the profile already has one for the variable
persist_var() {
  local name=$1
  local value=$2

  if grep -q "^export ${name}=" "$PROFILE_FILE" 2> /dev/null; then
    log "${name} is already exported in ${PROFILE_FILE}"
    return 0
  fi

  # %q quotes the value so that the profile keeps it as text when it is sourced
  local line
  printf -v line 'export %s=%q' "$name" "$value"
  if is_yes "$DRY_RUN"; then
    echo "[DRY RUN] echo '${line}' >> ${PROFILE_FILE}"
    return 0
  fi
  echo "$line" >> "$PROFILE_FILE" || die "Could not add ${name} to ${PROFILE_FILE}"
  log "Added ${name} to ${PROFILE_FILE}"
}

persist_env() {
  local name
  persist_var HARVEST_SOLR_HOME "${INSTALL_DIR}/${HARVEST_LINK}"
  persist_var CATALOG_SOLR_HOME "${INSTALL_DIR}/${CATALOG_LINK}"
  persist_var REGISTRY_MGR_SOLR_HOME "${INSTALL_DIR}/${REGISTRY_MGR_LINK}"
  for name in DATA_HOME BACKUP_HOME PDS4_SOLR_DOC_HOME PDS3_SOLR_DOC_HOME HARVEST_SOLR_CONF_HOME \
      PDS3_HISTORY PDS3_DATA_PATH REGISTRY_LOG_DIR; do
    if [[ -n "${!name:-}" ]]; then
      persist_var "$name" "${!name}"
    fi
  done
}

# The number of documents in the data collection, or nothing when Solr does not answer
solr_count() {
  curl --silent --fail "${SOLR_URL}/data/select?q=*:*&rows=0&wt=json" 2> /dev/null \
    | grep -o '"numFound":[0-9]*' | head -1 | cut -d':' -f2
}

wait_for_solr() {
  local attempt=0
  while [[ $attempt -lt 60 ]]; do
    if curl --silent --fail --output /dev/null "${SOLR_URL}/admin/info/system?wt=json"; then
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 5
  done
  return 1
}

# Runs a query against a handler of the data collection and prints the number of matches; fails when Solr
# does not answer or the answer has no result count
query_count() {
  local handler=$1
  local query=$2
  local count

  count=$(curl --silent --fail --max-time 60 -G "${SOLR_URL}/data/${handler}" \
    --data-urlencode "q=${query}" --data-urlencode "rows=1" --data-urlencode "facet=false" --data-urlencode "wt=json" 2> /dev/null \
    | grep -o '"numFound":[0-9]*' | head -1 | cut -d':' -f2)
  [[ -n "$count" ]] || return 1
  echo "$count"
}

# Runs a few queries against the handlers that the keyword search and the tools use. Each must answer; the
# match-all queries must find documents when the registry held some before the upgrade.
smoke_queries() {
  local before=$1
  local expect_docs=no
  local handler
  local query
  local count
  local pair

  [[ "${before:-0}" -gt 0 ]] && expect_docs=yes

  log "Running smoke queries against ${SOLR_URL}/data"
  for pair in 'select|*:*' 'all|*:*' 'keyword|*:*' 'keyword|mars' 'search|*:*' 'archive-filter|*:*'; do
    handler=${pair%%|*}
    query=${pair#*|}
    if ! count=$(query_count "$handler" "$query"); then
      warn "Smoke query FAILED: /${handler} q=${query} did not answer"
      SMOKE_FAILED=1
    elif [[ "$query" == '*:*' && "$expect_docs" == yes && "$count" -eq 0 ]]; then
      warn "Smoke query FAILED: /${handler} q=${query} found no documents, but the registry held ${before} before"
      SMOKE_FAILED=1
    else
      log "Smoke query ok: /${handler} q=${query} -> ${count} documents"
    fi
  done
}

# Reloads the registry with reload_all.sh of the new harvest, which loads the historical PDS3 documents, removes
# the legacy context products, then loads the PDS4 and PDS3 documents. It stops at the first step that fails.
reload_registry() {
  local venue=$1

  log "Reloading the registry: reload_all.sh ${venue}"
  run env REGISTRY_MGR_SOLR_HOME="${INSTALL_DIR}/${REGISTRY_MGR_LINK}" \
      HARVEST_SOLR_HOME="${INSTALL_DIR}/${HARVEST_LINK}" \
      CATALOG_SOLR_HOME="${INSTALL_DIR}/${CATALOG_LINK}" \
      "${INSTALL_DIR}/${HARVEST_LINK}/bin/reload_all.sh" "$venue" \
    || die "reload_all.sh ${venue} failed (see the messages above)"
}

# Installs or upgrades the registry with the registry manager that was just linked
registry_stage() {
  local installer="${INSTALL_DIR}/${REGISTRY_MGR_LINK}/bin/registry_legacy_installer_docker.sh"
  local action="install"
  local options=()
  local before=""

  if ! is_yes "$DRY_RUN"; then
    [[ -f "$installer" ]] || die "The installer is missing: $installer"
  fi

  # Something may have started while the release was downloaded; the upgrade restarts Solr
  check_nothing_running

  if docker inspect "$DOCKER_CONTAINER" > /dev/null 2>&1; then
    action="upgrade"
    before=$(solr_count)
    log "Registry container '${DOCKER_CONTAINER}' found with ${before:-an unknown number of} documents"
    is_yes "$SKIP_BACKUP" && options+=("-s")
  else
    log "No registry container '${DOCKER_CONTAINER}': installing the registry"
  fi

  export DATA_HOME
  [[ -n "${BACKUP_HOME:-}" ]] && export BACKUP_HOME
  log "Running the registry installer: ${action}"
  run bash "$installer" ${options[@]+"${options[@]}"} "$action" || die "The registry ${action} failed (see the messages above)"

  if is_yes "$DRY_RUN"; then
    return 0
  fi

  wait_for_solr || die "Solr did not answer at ${SOLR_URL} after the ${action}"
  local after
  after=$(solr_count)
  log "Solr answers with ${after:-an unknown number of} documents"
  if [[ "$action" == "upgrade" && -n "$before" && "$before" != "$after" ]]; then
    warn "The document count changed during the upgrade: ${before} before, ${after:-unknown} after"
  fi

  local image
  image=$(docker inspect --format '{{.Config.Image}}' "$DOCKER_CONTAINER" 2> /dev/null || true)
  log "Registry container image: ${image:-unknown}"
  [[ "$image" == *":${VERSION}" ]] || warn "The container image '${image}' is not tagged ${VERSION}"

  if [[ "$RELOAD_ALL" != "no" ]]; then
    reload_registry "$RELOAD_ALL"
  fi

  if is_yes "$SMOKE_QUERIES"; then
    smoke_queries "$before"
  fi
}

print_summary() {
  echo
  echo "---- Installed ---"
  echo "Version:       ${VERSION}"
  echo "INSTALL_DIR:   ${INSTALL_DIR}"
  echo "Harvest:       ${INSTALL_DIR}/${HARVEST_LINK}"
  echo "Catalog:       ${INSTALL_DIR}/${CATALOG_LINK}"
  echo "Registry mgr:  ${INSTALL_DIR}/${REGISTRY_MGR_LINK}"
  echo
  if [[ ${#ROLLBACK[@]} -gt 0 ]]; then
    echo "---- To go back to the previous tools ---"
    printf '%s\n' "${ROLLBACK[@]}"
    echo
  fi
  if is_yes "$UPGRADE_REGISTRY" && [[ "$RELOAD_ALL" == "no" ]]; then
    echo "---- Next ---"
    echo "Reload the registry only if this release adds schema fields or changes the harvest or search"
    echo "configuration, so that the documents already loaded gain the new values (see the release notes)."
    echo "reload_all.sh loads the historical PDS3 documents, removes the legacy context products, then loads the"
    echo "PDS4 and PDS3 documents, in that order. It needs REGISTRY_MGR_SOLR_HOME, PDS4_SOLR_DOC_HOME,"
    echo "PDS3_SOLR_DOC_HOME and PDS3_HISTORY to be set:"
    echo "  ${INSTALL_DIR}/${HARVEST_LINK}/bin/reload_all.sh prod      # load the documents generated on staging"
    echo "  ${INSTALL_DIR}/${HARVEST_LINK}/bin/reload_all.sh staging   # harvest and catalog first, then load"
    echo
  fi
  if [[ $SMOKE_FAILED -ne 0 ]]; then
    warn "Some smoke queries failed (see above). Check the registry before you use it."
    return 1
  fi
  log "Done."
}

validate_switches

if [[ "$VERSION" == "latest" ]]; then
  VERSION=$(resolve_latest) || exit 1
  log "The latest release is ${VERSION}"
fi

# Also checks that nothing is running, because a harvest that runs from the old tools is worth a pause too
preflight

is_yes "$DRY_RUN" && log "Dry run: nothing is downloaded, unpacked, linked or restarted"
log "Installing registry-legacy v${VERSION} into ${INSTALL_DIR}"

unpack_module catalog-legacy
unpack_module harvest-legacy
unpack_module registry-mgr-legacy

switch_link catalog-legacy "$CATALOG_LINK"
switch_link harvest-legacy "$HARVEST_LINK"
switch_link registry-mgr-legacy "$REGISTRY_MGR_LINK"

if is_yes "$PERSIST_ENV"; then
  persist_env
fi

if is_yes "$UPGRADE_REGISTRY"; then
  registry_stage
else
  log "UPGRADE_REGISTRY=no: the registry is not installed or upgraded"
fi

print_summary || exit 1
