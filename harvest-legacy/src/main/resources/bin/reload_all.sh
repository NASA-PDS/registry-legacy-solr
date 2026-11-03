#!/usr/bin/env bash
#
# Reloads the Legacy Solr Registry from the PDS4 and PDS3 data.
#
# Usage: reload_all.sh [staging|prod]
#
#   staging  Generate the PDS4 Solr documents (harvest-solr) and the PDS3 Solr documents
#            (catalog-solr), then load all documents into the Solr registry on this machine.
#   prod     Load the Solr documents that were generated on staging into the Solr registry on
#            this machine.
#
# The script stops at the first step that fails, so a failed harvest never leads to loading old
# or partial documents, and the previous PDS3 documents are only replaced by a successful catalog run.
#
# Required environment:
#   REGISTRY_MGR_SOLR_HOME  Registry Manager installation directory
#   PDS4_SOLR_DOC_HOME      Existing directory for the PDS4 Solr documents (solr-docs is created inside)
#   PDS3_SOLR_DOC_HOME      Existing directory for the PDS3 Solr documents (solr-docs is created inside)
#   PDS3_HISTORY            Directory with the historical PDS3 Solr documents
#
# Required environment for staging:
#   HARVEST_SOLR_HOME       Harvest installation directory
#   HARVEST_SOLR_CONF_HOME  Directory that holds harvest-policy-reload-all.xml
#   CATALOG_SOLR_HOME       Catalog installation directory
#   PDS3_DATA_PATH          Directory with the PDS3 data
#
# Optional environment:
#   REGISTRY_LOG_DIR        Directory in which harvest-logs and catalog-logs are created
#                           (default: $HOME/registry-logs)
#   SOLR_HOST, SOLR_PORT    The Solr registry to load (default: localhost and 8983)

set -u -o pipefail

SOLR_HOST=${SOLR_HOST:-localhost}
SOLR_PORT=${SOLR_PORT:-8983}
REGISTRY_LOG_DIR=${REGISTRY_LOG_DIR:-$HOME/registry-logs}

# Documents that the historical PDS3 data includes by mistake, which are removed after it is loaded
LEGACY_CONTEXT_QUERY='data_class:Facility OR data_class:Instrument OR data_class:Instrument_Host OR data_class:Target OR data_class:Telescope OR data_class:Investigation'

die() {
  echo "ERROR: $*" >&2
  exit 1
}

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*"
}

# Stops with a list of every variable that is not set
require_env() {
  local missing=()
  local name
  for name in "$@"; do
    if [[ -z "${!name:-}" ]]; then
      missing+=("$name")
    fi
  done
  if (( ${#missing[@]} > 0 )); then
    die "These environment variables must be set: ${missing[*]}"
  fi
}

# Succeeds if the directory holds at least one Solr document file
has_docs() {
  local dir=$1
  [[ -d "$dir" ]] && [[ -n "$(find "$dir" -type f -name '*.xml' 2>/dev/null | head -n 1)" ]]
}

# Only one reload may run at a time
acquire_lock() {
  LOCK_DIR="$PDS4_SOLR_DOC_HOME/reload_all.lock"
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    die "Another reload appears to be running. If none is, remove $LOCK_DIR."
  fi
  trap 'rmdir "$LOCK_DIR" 2>/dev/null' EXIT
}

# Generates the PDS4 Solr documents
harvest_pds4() {
  local log_dir="$REGISTRY_LOG_DIR/harvest-logs"
  local log_file
  mkdir -p "$log_dir" || die "Cannot create $log_dir"
  log_file="$log_dir/harvest_$(date +%Y%m%d%H%M%S).txt"

  log "Processing PDS4 data (harvest-solr). See log file: $log_file"
  "$HARVEST_SOLR_HOME/bin/harvest-solr" -c "$HARVEST_SOLR_CONF_HOME/harvest-policy-reload-all.xml" \
      -o "$PDS4_SOLR_DOC_HOME" > "$log_file" 2>&1 || die "harvest-solr failed. See $log_file"

  # harvest-solr reports some failures without a failing exit status, so also require its output
  has_docs "$PDS4_SOLR_DOC_HOME/solr-docs" \
      || die "harvest-solr produced no Solr documents in $PDS4_SOLR_DOC_HOME/solr-docs. See $log_file"
}

# Generates the PDS3 Solr documents. They are written to a new directory that replaces the current
# solr-docs only when the run succeeds; the current one is kept as solr-docs_pre<timestamp>.
catalog_pds3() {
  local log_dir="$REGISTRY_LOG_DIR/catalog-logs"
  local new_docs="$PDS3_SOLR_DOC_HOME/solr-docs_new"
  local log_file
  mkdir -p "$log_dir" || die "Cannot create $log_dir"
  log_file="$log_dir/catalog_$(date +%Y%m%d%H%M%S).txt"

  log "Processing PDS3 data (catalog-solr). See log file: $log_file"
  rm -rf "$new_docs"
  mkdir -p "$new_docs" || die "Cannot create $new_docs"
  "$CATALOG_SOLR_HOME/bin/catalog-solr" --mode ingest --doc-config "$CATALOG_SOLR_HOME/search-conf/defaults/" \
      --output-dir "$new_docs" --target "$PDS3_DATA_PATH" > "$log_file" 2>&1 \
      || die "catalog-solr failed. The previous PDS3 documents were left in place. See $log_file"
  has_docs "$new_docs" \
      || die "catalog-solr produced no Solr documents. The previous PDS3 documents were left in place. See $log_file"

  if [[ -d "$PDS3_SOLR_DOC_HOME/solr-docs" ]]; then
    mv "$PDS3_SOLR_DOC_HOME/solr-docs" "$PDS3_SOLR_DOC_HOME/solr-docs_pre$(date +%Y%m%dT%H%M%SZ)" \
        || die "Cannot back up $PDS3_SOLR_DOC_HOME/solr-docs"
  fi
  mv "$new_docs" "$PDS3_SOLR_DOC_HOME/solr-docs" || die "Cannot move $new_docs into place"
}

# Loads a directory of Solr documents into the registry
load_docs() {
  local label=$1
  local dir=$2

  has_docs "$dir" || die "There are no Solr documents to load for the $label in $dir"
  log "Loading $label..."
  "$REGISTRY_MGR_SOLR_HOME/bin/registry-mgr-solr" -host "$SOLR_HOST" -port "$SOLR_PORT" "$dir" \
      || die "Loading the $label failed"
}

delete_legacy_context_products() {
  log "Deleting the legacy context products that the historical PDS3 data includes..."
  curl --silent --show-error --fail -X POST -H 'Content-Type: application/json' \
      --data-binary "{\"delete\":{\"query\":\"$LEGACY_CONTEXT_QUERY\"}}" \
      "http://$SOLR_HOST:$SOLR_PORT/solr/data/update?commit=true" > /dev/null \
      || die "Deleting the legacy context products failed"
}

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 [staging|prod]" >&2
  exit 1
fi

venue=$1
if [[ "$venue" != "staging" && "$venue" != "prod" ]]; then
  die "Environment must be either 'staging' or 'prod'"
fi

if [[ "$venue" == "staging" ]]; then
  require_env REGISTRY_MGR_SOLR_HOME PDS4_SOLR_DOC_HOME PDS3_SOLR_DOC_HOME PDS3_HISTORY \
      HARVEST_SOLR_HOME HARVEST_SOLR_CONF_HOME CATALOG_SOLR_HOME PDS3_DATA_PATH
else
  require_env REGISTRY_MGR_SOLR_HOME PDS4_SOLR_DOC_HOME PDS3_SOLR_DOC_HOME PDS3_HISTORY
fi
[[ -d "$PDS4_SOLR_DOC_HOME" ]] || die "PDS4_SOLR_DOC_HOME is not a directory: $PDS4_SOLR_DOC_HOME"
[[ -d "$PDS3_SOLR_DOC_HOME" ]] || die "PDS3_SOLR_DOC_HOME is not a directory: $PDS3_SOLR_DOC_HOME"

acquire_lock

# Only staging generates the Solr documents; prod loads the ones staging generated
if [[ "$venue" == "staging" ]]; then
  harvest_pds4
  catalog_pds3
fi

load_docs "historical PDS3 data" "$PDS3_HISTORY"
delete_legacy_context_products
load_docs "PDS4 data" "$PDS4_SOLR_DOC_HOME/solr-docs"
load_docs "PDS3 data" "$PDS3_SOLR_DOC_HOME/solr-docs"

log "DONE"
