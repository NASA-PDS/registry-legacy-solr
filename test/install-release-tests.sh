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
SLEEPER_PID=""

cleanup() {
  [[ -n "$SERVER_PID" ]] && kill "$SERVER_PID" 2> /dev/null
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

echo '{"response":{"numFound":42}}' > "$WORK/solr/data/select"
echo '{}' > "$WORK/solr/admin/info/system"
PORT=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$WORK/solr" > /dev/null 2>&1 &
SERVER_PID=$!
sleep 1

LOG="$WORK/stub.log"
INSTALL="$WORK/install"

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
run_install "$SCRIPT" latest
expect_rc 1 "a name that is not a release number is refused"
run_install UPGRADE_REGISTRY=yes "$SCRIPT" 9.9.9
expect_rc 1 "the registry stage without DATA_HOME is refused"
expect_out "DATA_HOME must be set" "the missing DATA_HOME is explained"
[[ ! -e "$INSTALL" ]] && pass "nothing was created" || fail "INSTALL_DIR was created by a refused run"
run_install UPGRADE_REGISTRY=no LOAD_DOCS=yes "$SCRIPT" 9.9.9
expect_rc 1 "LOAD_DOCS without the registry stage is refused"

echo "TEST: a release that was not published"
run_install UPGRADE_REGISTRY=no "$SCRIPT" 8.8.8
expect_rc 1 "a missing release fails"
expect_out "has release v8.8.8 been published" "the failure names the release"
[[ -z "$(find "$INSTALL" -name '*.part' 2> /dev/null)" ]] && pass "no partial download is left" || fail "a .part file is left"

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
expect_out "Load the Solr documents again" "the next step is printed"

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

echo "TEST: documents are loaded when asked to"
mkdir -p "$WORK/pds4/solr-docs" "$WORK/pds3/solr-docs" "$WORK/history"
for dir in "$WORK/pds4/solr-docs" "$WORK/pds3/solr-docs" "$WORK/history"; do echo "<add/>" > "$dir/doc.xml"; done
: > "$LOG"
run_install DATA_HOME="$WORK/data" LOAD_DOCS=yes PDS4_SOLR_DOC_HOME="$WORK/pds4" \
  PDS3_SOLR_DOC_HOME="$WORK/pds3" PDS3_HISTORY="$WORK/history" "$SCRIPT" 9.9.9
expect_rc 0 "the load succeeds"
expect_log "load $WORK/pds4/solr-docs" "the PDS4 documents are loaded"
expect_log "load $WORK/pds3/solr-docs" "the PDS3 documents are loaded"
expect_log "load $WORK/history" "the historical documents are loaded"
rm "$WORK/pds4/solr-docs/doc.xml"
run_install DATA_HOME="$WORK/data" LOAD_DOCS=yes PDS4_SOLR_DOC_HOME="$WORK/pds4" PDS3_SOLR_DOC_HOME="$WORK/pds3" "$SCRIPT" 9.9.9
expect_rc 1 "an empty document directory is refused"
expect_out "no Solr documents to load" "the empty directory is named"

echo "TEST: environment variables are added to the profile once"
rm -f "$WORK/home/profile"
run_install UPGRADE_REGISTRY=no PERSIST_ENV=yes DATA_HOME="$WORK/data" "$SCRIPT" 9.9.9
expect_rc 0 "the install succeeds"
grep -q "^export HARVEST_SOLR_HOME=\"$INSTALL/harvest-legacy\"" "$WORK/home/profile" && pass "HARVEST_SOLR_HOME is exported" || fail "HARVEST_SOLR_HOME is missing"
grep -q "^export CATALOG_SOLR_HOME=\"$INSTALL/catalog-legacy\"" "$WORK/home/profile" && pass "CATALOG_SOLR_HOME is its own directory" || fail "CATALOG_SOLR_HOME is wrong"
grep -q "^export REGISTRY_MGR_SOLR_HOME=\"$INSTALL/registry-manager-legacy\"" "$WORK/home/profile" && pass "REGISTRY_MGR_SOLR_HOME is exported" || fail "REGISTRY_MGR_SOLR_HOME is missing"
grep -q "^export DATA_HOME=" "$WORK/home/profile" && pass "variables set for the run are exported" || fail "DATA_HOME is missing"
run_install UPGRADE_REGISTRY=no PERSIST_ENV=yes DATA_HOME="$WORK/data" "$SCRIPT" 9.9.9
[[ "$(grep -c '^export HARVEST_SOLR_HOME=' "$WORK/home/profile")" -eq 1 ]] && pass "a second run adds nothing" || fail "the export was repeated"

echo "TEST: it will not touch Solr while a harvest runs"
# Two commands, so that bash stays alive and its arguments name a harvest
bash -c 'sleep 60; echo harvest-solr' &
SLEEPER_PID=$!
sleep 1
run_install UPGRADE_REGISTRY=no "$SCRIPT" 9.9.9
expect_rc 1 "the run is refused"
expect_out "are running" "the running process is named"
run_install UPGRADE_REGISTRY=no FORCE=yes "$SCRIPT" 9.9.9
expect_rc 0 "FORCE=yes goes on"
kill "$SLEEPER_PID" 2> /dev/null
SLEEPER_PID=""

echo
echo "Passed: $PASSED  Failed: $FAILED"
[[ $FAILED -eq 0 ]]
