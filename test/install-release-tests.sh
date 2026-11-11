#!/usr/bin/env bash
#
# Tests for scripts/install-release.sh.
#
# The release is a set of tiny tarballs that are "downloaded" through a file:// URL, docker is a stub, and Solr
# is a throwaway local HTTP server, so nothing is downloaded and no registry is touched. The tests refuse to
# run next to a real harvest or load (the installer would refuse too), so run them on a machine where none is
# running. Run from anywhere:
#
#   test/install-release-tests.sh

# pass and fail always succeed, so "A && pass || fail" is a safe if-then-else here; the stubs are written
# with single quotes on purpose, so that they expand when they run
# shellcheck disable=SC2015,SC2016
set -u -o pipefail

TEST_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SCRIPT="$TEST_DIR/../scripts/install-release.sh"
WORK=$(mktemp -d)
SERVER_PID=""
REDIRECT_PID=""
SLEEPER_PID=""

cleanup() {
  [[ -n "$SERVER_PID" ]] && kill "$SERVER_PID" 2> /dev/null
  [[ -n "$REDIRECT_PID" ]] && kill "$REDIRECT_PID" 2> /dev/null
  [[ -n "$SLEEPER_PID" ]] && kill "$SLEEPER_PID" 2> /dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT

PASSED=0
FAILED=0

pass() { PASSED=$((PASSED + 1)); echo "  PASS: $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  FAIL: $1"; }

expect_rc() { [[ $RC -eq $1 ]] && pass "$2" || fail "$2 (exit status $RC, expected $1)"; }
expect_out() { [[ "$OUT" == *"$1"* ]] && pass "$2" || fail "$2 (output lacks: $1)"; }
expect_no_out() { [[ "$OUT" != *"$1"* ]] && pass "$2" || fail "$2 (output has: $1)"; }
expect_link() { [[ "$(readlink "$1")" == "$2" ]] && pass "$3" || fail "$3 ($(readlink "$1") is not $2)"; }
expect_log() { grep -q -- "$1" "$LOG" && pass "$2" || fail "$2 (no log line matching: $1)"; }
expect_no_log() { grep -q -- "$1" "$LOG" && fail "$2 (unexpected log line: $1)" || pass "$2"; }

# Builds the tarballs of a fake release: <module>-<version>-bin.tar.gz holding <module>-<version>/bin/...
make_release() {
  local version=$1
  local module
  mkdir -p "$WORK/release/v${version}"
  for module in catalog-legacy harvest-legacy registry-mgr-legacy; do
    mkdir -p "$WORK/build/${module}-${version}/bin"
    echo "$module $version" > "$WORK/build/${module}-${version}/bin/tool"
    if [[ "$module" == "harvest-legacy" ]]; then
      printf '#!/bin/sh\necho "reload $* manager=$REGISTRY_MGR_SOLR_HOME" >> "%s"\nexit ${STUB_RELOAD_EXIT:-0}\n' "$LOG" \
        > "$WORK/build/${module}-${version}/bin/reload_all.sh"
      chmod +x "$WORK/build/${module}-${version}/bin/reload_all.sh"
    fi
    tar --create --gzip --file "$WORK/release/v${version}/${module}-${version}-bin.tar.gz" \
        --directory "$WORK/build" "${module}-${version}"
  done
}

# Stubs: docker, plus the scripts the registry manager would bring
mkdir -p "$WORK/stubs" "$WORK/solr/data" "$WORK/solr/admin/info" "$WORK/home"
cat > "$WORK/stubs/docker" <<'EOF'
#!/bin/sh
if [ "$1" = "inspect" ]; then
  [ "$STUB_CONTAINER" = "yes" ] || exit 1
  [ "$2" = "--format" ] && echo "registry-legacy:$STUB_IMAGE_VERSION"
  exit 0
fi
exit 0
EOF
chmod +x "$WORK/stubs/docker"

for handler in select all keyword search archive-filter; do
  echo '{"response":{"numFound":42}}' > "$WORK/solr/data/$handler"
done
echo '{}' > "$WORK/solr/admin/info/system"
PORT=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$WORK/solr" > /dev/null 2>&1 &
SERVER_PID=$!
sleep 1

# A stand-in for the latest release page of GitHub: it redirects to the page of release 9.9.10
cat > "$WORK/redirect.py" <<'EOF'
import http.server
import sys

port = int(sys.argv[1])


class Handler(http.server.BaseHTTPRequestHandler):
    def do_HEAD(self):
        if self.path.endswith("/latest"):
            self.send_response(302)
            self.send_header("Location", "http://127.0.0.1:%d/releases/tag/v9.9.10" % port)
        else:
            self.send_response(200)
        self.end_headers()

    do_GET = do_HEAD

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
EOF
REDIRECT_PORT=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
python3 "$WORK/redirect.py" "$REDIRECT_PORT" > /dev/null 2>&1 &
REDIRECT_PID=$!
sleep 1

LOG="$WORK/stub.log"
INSTALL="$WORK/install"

# Stubs that break one command, to be put first on the PATH of a single run
mkdir -p "$WORK/pgrep-broken" "$WORK/pgrep-late" "$WORK/ln-broken"
printf '#!/bin/sh\nexit 2\n' > "$WORK/pgrep-broken/pgrep"
# Reports a harvest from its second call on, as if one started while the release was downloaded
cat > "$WORK/pgrep-late/pgrep" <<'EOF'
#!/bin/sh
n=$(cat "$STUB_COUNT" 2> /dev/null || echo 0)
echo $((n + 1)) > "$STUB_COUNT"
[ "$n" -ge 1 ] || exit 1
echo "4242 harvest-solr"
EOF
printf '#!/bin/sh\nexit 1\n' > "$WORK/ln-broken/ln"
chmod +x "$WORK/pgrep-broken/pgrep" "$WORK/pgrep-late/pgrep" "$WORK/ln-broken/ln"

# The documents and directories that RELOAD_ALL needs
mkdir -p "$WORK/pds4/solr-docs" "$WORK/pds3/solr-docs" "$WORK/history" "$WORK/conf" "$WORK/pds3data"
for dir in "$WORK/pds4/solr-docs" "$WORK/pds3/solr-docs" "$WORK/history"; do
  echo '<add/>' > "$dir/doc.xml"
done

# Makes the registry manager of a fake release record its calls
stub_registry_manager() {
  local version=$1
  local bin="$WORK/build/registry-mgr-legacy-${version}/bin"
  printf '#!/bin/sh\necho "installer $*" >> "%s"\nexit ${STUB_INSTALLER_EXIT:-0}\n' "$LOG" > "$bin/registry_legacy_installer_docker.sh"
  printf '#!/bin/sh\necho "load $*" >> "%s"\nexit 0\n' "$LOG" > "$bin/registry-mgr-solr"
  chmod +x "$bin/registry_legacy_installer_docker.sh" "$bin/registry-mgr-solr"
  tar --create --gzip --file "$WORK/release/v${version}/registry-mgr-legacy-${version}-bin.tar.gz" \
      --directory "$WORK/build" "registry-mgr-legacy-${version}"
}

# Runs the installer with a clean environment plus the given variables; the output is in $OUT, the status in $RC
run_install() {
  OUT=$(env -i HOME="$WORK/home" PATH="$WORK/stubs:$PATH" RELEASE_URL_BASE="file://$WORK/release" \
        INSTALL_DIR="$INSTALL" SOLR_URL="http://127.0.0.1:$PORT" PROFILE_FILE="$WORK/home/profile" \
        STUB_CONTAINER=yes STUB_IMAGE_VERSION=9.9.9 "$@" 2>&1)
  RC=$?
}

make_release 9.9.9
make_release 9.9.10
stub_registry_manager 9.9.9
stub_registry_manager 9.9.10

echo "TEST: bad input stops before anything changes"
run_install "$SCRIPT"
expect_rc 1 "no version is refused"
expect_out "Give the release" "the missing version is explained"
run_install "$SCRIPT" stable
expect_rc 1 "a name that is not a release number is refused"
run_install UPGRADE_REGISTRY=yes "$SCRIPT" 9.9.9
expect_rc 1 "the registry stage without DATA_HOME is refused"
expect_out "DATA_HOME must be set" "the missing DATA_HOME is explained"
[[ ! -e "$INSTALL" ]] && pass "nothing was created" || fail "INSTALL_DIR was created by a refused run"
run_install UPGRADE_REGISTRY=no RELOAD_ALL=prod "$SCRIPT" 9.9.9
expect_rc 1 "RELOAD_ALL without the registry stage is refused"
run_install DATA_HOME="$WORK/data" RELOAD_ALL=yes "$SCRIPT" 9.9.9
expect_rc 1 "a RELOAD_ALL that is not prod or staging is refused"
expect_out "RELOAD_ALL must be no, prod or staging" "the allowed values are named"
run_install UPGRADE_REGISTRY=no DRY_RUN=ye "$SCRIPT" 9.9.9
expect_rc 1 "a DRY_RUN that is not yes or no is refused"
expect_out "DRY_RUN must be yes or no (found 'ye')" "the bad value is named"
run_install UPGRADE_REGISTRY=maybe "$SCRIPT" 9.9.9
expect_rc 1 "an UPGRADE_REGISTRY that is not yes or no is refused"
for name in SKIP_BACKUP SMOKE_QUERIES PERSIST_ENV FORCE; do
  run_install UPGRADE_REGISTRY=no "$name=Yes" "$SCRIPT" 9.9.9
  expect_rc 1 "a $name that is not yes or no is refused"
done
run_install PATH="$WORK/pgrep-broken:$PATH" UPGRADE_REGISTRY=no "$SCRIPT" 9.9.9
expect_rc 1 "a pgrep that cannot look is refused"
expect_out "pgrep failed" "the failing guard is explained"
[[ ! -e "$INSTALL" ]] && pass "nothing was created" || fail "INSTALL_DIR was created by a refused run"
run_install UPGRADE_REGISTRY=no PERSIST_ENV=yes PROFILE_FILE="$WORK/home" "$SCRIPT" 9.9.9
expect_rc 1 "a profile that is a directory is refused"
expect_out "PROFILE_FILE is not a writable file" "the profile is named"
[[ ! -e "$INSTALL" ]] && pass "nothing was created" || fail "INSTALL_DIR was created by a refused run"

echo "TEST: the documents of a reload are checked before the registry is touched"
: > "$LOG"
run_install DATA_HOME="$WORK/data" RELOAD_ALL=prod PDS4_SOLR_DOC_HOME="$WORK/nowhere" \
  PDS3_SOLR_DOC_HOME="$WORK/pds3" PDS3_HISTORY="$WORK/history" "$SCRIPT" 9.9.9
expect_rc 1 "a missing PDS4_SOLR_DOC_HOME is refused"
expect_out "PDS4_SOLR_DOC_HOME is not a directory" "the directory is named"
run_install DATA_HOME="$WORK/data" RELOAD_ALL=prod PDS4_SOLR_DOC_HOME="$WORK/pds4" \
  PDS3_SOLR_DOC_HOME="$WORK/pds3" PDS3_HISTORY="$WORK/conf" "$SCRIPT" 9.9.9
expect_rc 1 "a PDS3_HISTORY without documents is refused"
expect_out "no Solr documents (*.xml) in PDS3_HISTORY" "the empty directory is named"
mkdir -p "$WORK/empty-docs"
run_install DATA_HOME="$WORK/data" RELOAD_ALL=prod PDS4_SOLR_DOC_HOME="$WORK/empty-docs" \
  PDS3_SOLR_DOC_HOME="$WORK/pds3" PDS3_HISTORY="$WORK/history" "$SCRIPT" 9.9.9
expect_rc 1 "a prod reload without generated documents is refused"
expect_out "there are none in $WORK/empty-docs/solr-docs" "the missing documents are named"
run_install DATA_HOME="$WORK/data" RELOAD_ALL=staging PDS4_SOLR_DOC_HOME="$WORK/pds4" PDS3_SOLR_DOC_HOME="$WORK/pds3" \
  PDS3_HISTORY="$WORK/history" HARVEST_SOLR_CONF_HOME="$WORK/conf" PDS3_DATA_PATH="$WORK/nowhere" "$SCRIPT" 9.9.9
expect_rc 1 "a staging reload without the PDS3 data is refused"
expect_out "PDS3_DATA_PATH is not a directory" "the directory is named"
expect_no_log "installer" "the registry was not touched by any refused run"
[[ ! -e "$INSTALL" ]] && pass "nothing was created" || fail "INSTALL_DIR was created by a refused run"

echo "TEST: a release that was not published"
run_install UPGRADE_REGISTRY=no "$SCRIPT" 8.8.8
expect_rc 1 "a missing release fails"
expect_out "has release v8.8.8 been published" "the failure names the release"
[[ -z "$(find "$INSTALL" -name '*.part' 2> /dev/null)" ]] && pass "no partial download is left" || fail "a .part file is left"

echo "TEST: latest finds the newest release"
run_install UPGRADE_REGISTRY=no RELEASES_LATEST_URL="http://127.0.0.1:$REDIRECT_PORT/releases/latest" "$SCRIPT" latest
expect_rc 0 "latest installs"
expect_out "The latest release is 9.9.10" "the release it found is named"
expect_link "$INSTALL/harvest-legacy" "harvest-legacy-9.9.10" "the links point at the latest release"
rm -rf "$INSTALL"
run_install UPGRADE_REGISTRY=no RELEASES_LATEST_URL="http://127.0.0.1:1/releases/latest" "$SCRIPT" latest
expect_rc 1 "latest fails when the release page cannot be read"
expect_out "latest release" "the failure is explained"
[[ ! -e "$INSTALL" ]] && pass "nothing was created" || fail "INSTALL_DIR was created by a failed lookup"
rm -rf "$INSTALL"

echo "TEST: first install of the tools"
run_install UPGRADE_REGISTRY=no "$SCRIPT" 9.9.9
expect_rc 0 "the install succeeds"
for pair in catalog-legacy:catalog-legacy harvest-legacy:harvest-legacy registry-mgr-legacy:registry-manager-legacy; do
  module=${pair%%:*}
  link=${pair##*:}
  expect_link "$INSTALL/$link" "${module}-9.9.9" "$link points at ${module}-9.9.9"
  [[ -f "$INSTALL/$link/bin/tool" || -f "$INSTALL/$link/bin/registry-mgr-solr" ]] && pass "$link resolves to the unpacked release" || fail "$link does not resolve"
done

echo "TEST: running it again changes nothing"
run_install UPGRADE_REGISTRY=no "$SCRIPT" 9.9.9
expect_rc 0 "the second run succeeds"
expect_out "already unpacked" "the unpacked release is reused"
expect_out "already points at" "the links are left alone"

echo "TEST: moving to a newer release keeps the old one and says how to go back"
run_install UPGRADE_REGISTRY=no "$SCRIPT" 9.9.10
expect_rc 0 "the newer release installs"
expect_link "$INSTALL/harvest-legacy" "harvest-legacy-9.9.10" "the link moved to 9.9.10"
[[ -d "$INSTALL/harvest-legacy-9.9.9" ]] && pass "the previous release stays on disk" || fail "the previous release was removed"
expect_out "ln -sfn 'harvest-legacy-9.9.9'" "the rollback command is printed"
(cd "$INSTALL" && ln -sfn harvest-legacy-9.9.9 harvest-legacy)
expect_link "$INSTALL/harvest-legacy" "harvest-legacy-9.9.9" "the printed rollback works"

echo "TEST: an unpacked release without a bin directory is not reused"
mv "$INSTALL/harvest-legacy-9.9.10/bin" "$INSTALL/harvest-legacy-9.9.10/bin.off"
run_install UPGRADE_REGISTRY=no "$SCRIPT" 9.9.10
expect_rc 1 "the run is refused"
expect_out "has no bin directory" "the incomplete release is named"
expect_link "$INSTALL/harvest-legacy" "harvest-legacy-9.9.9" "the links were not moved"
mv "$INSTALL/harvest-legacy-9.9.10/bin.off" "$INSTALL/harvest-legacy-9.9.10/bin"

echo "TEST: a link that cannot be switched fails the run"
run_install PATH="$WORK/ln-broken:$PATH" UPGRADE_REGISTRY=no "$SCRIPT" 9.9.10
expect_rc 1 "the failure is passed on"
expect_out "Could not point" "the link is named"
expect_no_out "Done." "no success is reported"

echo "TEST: a real directory in place of a link is never replaced"
mv "$INSTALL/catalog-legacy" "$INSTALL/catalog-legacy.link"
mkdir "$INSTALL/catalog-legacy"
run_install UPGRADE_REGISTRY=no "$SCRIPT" 9.9.10
expect_rc 1 "the run is refused"
expect_out "is not a symlink" "the reason is given"
rmdir "$INSTALL/catalog-legacy"
mv "$INSTALL/catalog-legacy.link" "$INSTALL/catalog-legacy"

echo "TEST: nothing changes in a dry run"
rm -rf "$INSTALL"
run_install UPGRADE_REGISTRY=no DRY_RUN=yes "$SCRIPT" 9.9.9
expect_rc 0 "the dry run succeeds"
expect_out "[DRY RUN]" "the actions are printed"
[[ -z "$(ls -A "$INSTALL" 2> /dev/null)" ]] && pass "nothing was downloaded or linked" || fail "a dry run changed INSTALL_DIR"

echo "TEST: the registry is upgraded when its container exists"
rm -rf "$INSTALL"
: > "$LOG"
run_install DATA_HOME="$WORK/data" "$SCRIPT" 9.9.9
expect_rc 0 "the install and upgrade succeed"
expect_log "installer upgrade" "the installer runs the upgrade"
expect_no_log "installer -s" "the backup is not skipped by default"
expect_out "Solr answers with 42 documents" "the document count is checked afterwards"
expect_no_out "is not tagged" "the container image matches the release"
expect_out "Reload the registry only if this release adds schema fields" "the reload is described as optional"
expect_out "Smoke query ok: /keyword q=*:* -> 42 documents" "the keyword handler is queried after the upgrade"
for handler in select all search archive-filter; do
  expect_out "Smoke query ok: /${handler} q=*:*" "the $handler handler is queried after the upgrade"
done

echo "TEST: a smoke query that fails fails the run"
mv "$WORK/solr/data/keyword" "$WORK/solr/data/keyword.off"
run_install DATA_HOME="$WORK/data" "$SCRIPT" 9.9.9
expect_rc 1 "a handler that does not answer fails the run"
expect_out "Smoke query FAILED: /keyword" "the failing handler is named"
expect_out "Some smoke queries failed" "the failure is summarized"
expect_out "Installed" "the summary is still printed"
echo '{"response":{"numFound":0}}' > "$WORK/solr/data/keyword"
run_install DATA_HOME="$WORK/data" "$SCRIPT" 9.9.9
expect_rc 1 "a handler that finds nothing in a registry that held documents fails the run"
expect_out "found no documents" "the empty answer is explained"
rm "$WORK/solr/data/keyword"
mv "$WORK/solr/data/keyword.off" "$WORK/solr/data/keyword"

echo "TEST: the smoke queries can be skipped"
run_install DATA_HOME="$WORK/data" SMOKE_QUERIES=no "$SCRIPT" 9.9.9
expect_rc 0 "the run succeeds"
expect_no_out "Smoke query" "no query is run"

echo "TEST: the backup can be skipped, and a missing container means an install"
: > "$LOG"
run_install DATA_HOME="$WORK/data" SKIP_BACKUP=yes "$SCRIPT" 9.9.9
expect_log "installer -s upgrade" "SKIP_BACKUP passes -s"
: > "$LOG"
run_install DATA_HOME="$WORK/data" STUB_CONTAINER=no "$SCRIPT" 9.9.9
expect_log "installer install" "no container means an install"
expect_no_log "installer upgrade" "no upgrade is run without a container"

echo "TEST: an image with the wrong tag is reported"
run_install DATA_HOME="$WORK/data" STUB_IMAGE_VERSION=9.9.8 "$SCRIPT" 9.9.9
expect_rc 0 "the run still completes"
expect_out "is not tagged 9.9.9" "the stale image is reported"

echo "TEST: a failed upgrade fails the run"
run_install DATA_HOME="$WORK/data" STUB_INSTALLER_EXIT=1 "$SCRIPT" 9.9.9
expect_rc 1 "the failure is passed on"
expect_out "upgrade failed" "the failure is named"

echo "TEST: the registry is reloaded with reload_all.sh when asked to"
: > "$LOG"
run_install DATA_HOME="$WORK/data" "$SCRIPT" 9.9.9
expect_no_log "reload" "nothing is reloaded by default"
expect_out "reload_all.sh prod" "the reload commands are printed as the next step"
expect_out "removes the legacy context products" "the order of the reload is explained"
: > "$LOG"
run_install DATA_HOME="$WORK/data" RELOAD_ALL=prod PDS4_SOLR_DOC_HOME="$WORK/pds4" \
  PDS3_SOLR_DOC_HOME="$WORK/pds3" PDS3_HISTORY="$WORK/history" "$SCRIPT" 9.9.9
expect_rc 0 "the reload succeeds"
expect_log "installer upgrade" "the registry is upgraded before the reload"
expect_log "reload prod manager=$INSTALL/registry-manager-legacy" "reload_all.sh prod runs with the new registry manager"
expect_no_log "^load " "the documents are not loaded one directory at a time"
expect_no_out "reload_all.sh prod      #" "no next step is printed after a reload"
: > "$LOG"
run_install DATA_HOME="$WORK/data" RELOAD_ALL=staging PDS4_SOLR_DOC_HOME="$WORK/pds4" \
  PDS3_SOLR_DOC_HOME="$WORK/pds3" PDS3_HISTORY="$WORK/history" HARVEST_SOLR_CONF_HOME="$WORK/conf" \
  PDS3_DATA_PATH="$WORK/pds3data" "$SCRIPT" 9.9.9
expect_rc 0 "the staging reload succeeds"
expect_log "reload staging" "reload_all.sh staging runs"
run_install DATA_HOME="$WORK/data" RELOAD_ALL=staging PDS4_SOLR_DOC_HOME="$WORK/pds4" \
  PDS3_SOLR_DOC_HOME="$WORK/pds3" PDS3_HISTORY="$WORK/history" "$SCRIPT" 9.9.9
expect_rc 1 "a staging reload without the harvest settings is refused"
expect_out "needs HARVEST_SOLR_CONF_HOME" "the missing setting is named"
run_install DATA_HOME="$WORK/data" RELOAD_ALL=prod PDS4_SOLR_DOC_HOME="$WORK/pds4" PDS3_SOLR_DOC_HOME="$WORK/pds3" "$SCRIPT" 9.9.9
expect_rc 1 "a reload without PDS3_HISTORY is refused"
expect_out "needs PDS3_HISTORY" "the missing setting is named"
run_install DATA_HOME="$WORK/data" RELOAD_ALL=prod STUB_RELOAD_EXIT=1 PDS4_SOLR_DOC_HOME="$WORK/pds4" \
  PDS3_SOLR_DOC_HOME="$WORK/pds3" PDS3_HISTORY="$WORK/history" "$SCRIPT" 9.9.9
expect_rc 1 "a failed reload fails the run"
expect_out "reload_all.sh prod failed" "the failure is named"
expect_no_out "Smoke query" "no smoke query runs after a failed reload"

echo "TEST: environment variables are added to the profile once"
rm -f "$WORK/home/profile"
run_install UPGRADE_REGISTRY=no PERSIST_ENV=yes DATA_HOME="$WORK/data" "$SCRIPT" 9.9.9
expect_rc 0 "the install succeeds"
# Sourcing the profile in a clean shell shows the values that a login would get
profile_value() { env -i bash -c 'source "$1" && printf %s "${!2}"' _ "$WORK/home/profile" "$1"; }
[[ "$(profile_value HARVEST_SOLR_HOME)" == "$INSTALL/harvest-legacy" ]] && pass "HARVEST_SOLR_HOME is exported" || fail "HARVEST_SOLR_HOME is missing"
[[ "$(profile_value CATALOG_SOLR_HOME)" == "$INSTALL/catalog-legacy" ]] && pass "CATALOG_SOLR_HOME is its own directory" || fail "CATALOG_SOLR_HOME is wrong"
[[ "$(profile_value REGISTRY_MGR_SOLR_HOME)" == "$INSTALL/registry-manager-legacy" ]] && pass "REGISTRY_MGR_SOLR_HOME is exported" || fail "REGISTRY_MGR_SOLR_HOME is missing"
[[ "$(profile_value DATA_HOME)" == "$WORK/data" ]] && pass "variables set for the run are exported" || fail "DATA_HOME is missing"
run_install UPGRADE_REGISTRY=no PERSIST_ENV=yes DATA_HOME="$WORK/data" "$SCRIPT" 9.9.9
[[ "$(grep -c '^export HARVEST_SOLR_HOME=' "$WORK/home/profile")" -eq 1 ]] && pass "a second run adds nothing" || fail "the export was repeated"

echo "TEST: values in the profile are text, never commands"
rm -f "$WORK/home/profile" "$WORK/injected"
run_install UPGRADE_REGISTRY=no PERSIST_ENV=yes DATA_HOME="\$(touch $WORK/injected) with space" "$SCRIPT" 9.9.9
expect_rc 0 "the install succeeds"
[[ "$(profile_value DATA_HOME)" == "\$(touch $WORK/injected) with space" ]] && pass "the value is kept as it was given" || fail "the value was changed"
[[ ! -e "$WORK/injected" ]] && pass "sourcing the profile runs nothing" || fail "sourcing the profile ran the value"

echo "TEST: it will not touch Solr while a harvest runs"
# Two commands, so that bash stays alive and its arguments name a harvest
bash -c 'sleep 60; echo harvest-solr' &
SLEEPER_PID=$!
sleep 1
rm -rf "$INSTALL"
run_install UPGRADE_REGISTRY=no "$SCRIPT" 9.9.9
expect_rc 1 "the run is refused"
expect_out "are running" "the running process is named"
[[ ! -e "$INSTALL" ]] && pass "the refused run created nothing" || fail "INSTALL_DIR was created by a refused run"
run_install UPGRADE_REGISTRY=no FORCE=yes "$SCRIPT" 9.9.9
expect_rc 0 "FORCE=yes goes on"
kill "$SLEEPER_PID" 2> /dev/null
SLEEPER_PID=""

echo "TEST: a harvest that starts during the download stops the upgrade"
rm -rf "$INSTALL" "$WORK/pgrep.count"
: > "$LOG"
run_install PATH="$WORK/pgrep-late:$PATH" STUB_COUNT="$WORK/pgrep.count" DATA_HOME="$WORK/data" "$SCRIPT" 9.9.9
expect_rc 1 "the run is refused before the registry is restarted"
expect_out "are running" "the running process is named"
expect_no_log "installer" "the registry installer did not run"

echo
echo "Passed: $PASSED  Failed: $FAILED"
[[ $FAILED -eq 0 ]]
