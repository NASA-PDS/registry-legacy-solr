#!/usr/bin/env bash
#
# Tests for harvest-legacy/src/main/resources/bin/reload_all.sh.
#
# The tools the script calls (harvest-solr, catalog-solr, registry-mgr-solr and curl) are replaced
# by stubs that record their calls, so no Solr or data is needed. Run from anywhere:
#
#   test/reload-all-tests.sh

# pass and fail always succeed, so "A && pass || fail" is a safe if-then-else here
# shellcheck disable=SC2015
set -u -o pipefail

TEST_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SCRIPT="$TEST_DIR/../harvest-legacy/src/main/resources/bin/reload_all.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

PASSED=0
FAILED=0

pass() { PASSED=$((PASSED + 1)); echo "  PASS: $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  FAIL: $1"; }

# Creates a fresh sandbox with stub tools and sets the environment the script needs
setup() {
  rm -rf "${WORK:?}/sandbox"
  local s="$WORK/sandbox"
  mkdir -p "$s/harvest/bin" "$s/catalog/bin" "$s/mgr/bin" "$s/stubbin" \
           "$s/pds4" "$s/pds3/solr-docs" "$s/history" "$s/conf" "$s/pds3-data"
  echo "<add/>" > "$s/history/history.xml"
  echo "<add/>" > "$s/pds3/solr-docs/old.xml"
  CALLS="$s/calls.log"
  : > "$CALLS"

  cat > "$s/harvest/bin/harvest-solr" <<'EOF'
#!/usr/bin/env bash
echo "harvest-solr $*" >> "$CALLS"
[[ -n "${FAIL_HARVEST:-}" ]] && { echo "harvest broke"; exit 1; }
out=""
while [[ $# -gt 0 ]]; do [[ "$1" == "-o" ]] && out=$2; shift; done
if [[ -z "${EMPTY_HARVEST:-}" ]]; then
  mkdir -p "$out/solr-docs"
  echo "<add/>" > "$out/solr-docs/pds4.xml"
fi
EOF
  cat > "$s/catalog/bin/catalog-solr" <<'EOF'
#!/usr/bin/env bash
echo "catalog-solr $*" >> "$CALLS"
[[ -n "${FAIL_CATALOG:-}" ]] && { echo "catalog broke"; exit 1; }
out=""
while [[ $# -gt 0 ]]; do [[ "$1" == "--output-dir" ]] && out=$2; shift; done
echo "<add/>" > "$out/pds3-new.xml"
EOF
  cat > "$s/mgr/bin/registry-mgr-solr" <<'EOF'
#!/usr/bin/env bash
echo "registry-mgr-solr $*" >> "$CALLS"
[[ -n "${FAIL_LOAD_MATCH:-}" && "$*" == *"$FAIL_LOAD_MATCH"* ]] && exit 1
exit 0
EOF
  cat > "$s/stubbin/curl" <<'EOF'
#!/usr/bin/env bash
echo "curl $*" >> "$CALLS"
[[ -n "${FAIL_CURL:-}" ]] && exit 22
exit 0
EOF
  chmod +x "$s"/harvest/bin/* "$s"/catalog/bin/* "$s"/mgr/bin/* "$s"/stubbin/*

  export CALLS
  export PATH="$s/stubbin:$ORIGINAL_PATH"
  export REGISTRY_MGR_SOLR_HOME="$s/mgr"
  export PDS4_SOLR_DOC_HOME="$s/pds4"
  export PDS3_SOLR_DOC_HOME="$s/pds3"
  export PDS3_HISTORY="$s/history"
  export HARVEST_SOLR_HOME="$s/harvest"
  export HARVEST_SOLR_CONF_HOME="$s/conf"
  export CATALOG_SOLR_HOME="$s/catalog"
  export PDS3_DATA_PATH="$s/pds3-data"
  export REGISTRY_LOG_DIR="$s/logs"
  unset FAIL_HARVEST EMPTY_HARVEST FAIL_CATALOG FAIL_LOAD_MATCH FAIL_CURL SOLR_HOST SOLR_PORT
}

# Succeeds if the PDS3 directory holds a solr-docs_pre<timestamp> backup
has_pds3_backup() {
  compgen -G "$PDS3_SOLR_DOC_HOME/solr-docs_pre*" > /dev/null
}

# Prod loads the PDS4 documents that staging generated earlier
with_pds4_docs() {
  mkdir -p "$PDS4_SOLR_DOC_HOME/solr-docs"
  echo "<add/>" > "$PDS4_SOLR_DOC_HOME/solr-docs/pds4.xml"
}

# Runs the script and keeps its output in $OUT and its exit status in $RC
run() {
  OUT=$("$SCRIPT" "$@" 2>&1)
  RC=$?
}

expect_rc() { [[ $RC -eq $1 ]] && pass "$2" || fail "$2 (exit status $RC, expected $1)"; }
expect_out() { [[ "$OUT" == *"$1"* ]] && pass "$2" || fail "$2 (output lacks: $1)"; }
expect_called() { grep -q -- "$1" "$CALLS" && pass "$2" || fail "$2 (no call matching: $1)"; }
expect_not_called() { grep -q -- "$1" "$CALLS" && fail "$2 (unexpected call: $1)" || pass "$2"; }

ORIGINAL_PATH=$PATH

echo "TEST: arguments"
setup
run
expect_rc 1 "no argument is rejected"
run dev
expect_rc 1 "an unknown environment is rejected"

echo "TEST: missing environment"
setup
unset PDS3_DATA_PATH CATALOG_SOLR_HOME
run staging
expect_rc 1 "staging stops when variables are missing"
expect_out "CATALOG_SOLR_HOME PDS3_DATA_PATH" "every missing variable is listed"
[[ ! -s "$CALLS" ]] && pass "nothing ran" || fail "tools ran with missing variables"
setup
unset PDS3_DATA_PATH CATALOG_SOLR_HOME HARVEST_SOLR_HOME
with_pds4_docs
run prod
expect_rc 0 "prod does not need the harvest and catalog variables"

echo "TEST: staging success"
setup
run staging
expect_rc 0 "staging succeeds"
expect_out "DONE" "reports DONE"
order=$(grep -o '^[a-z-]*' "$CALLS" | tr '\n' ' ')
[[ "$order" == "harvest-solr catalog-solr registry-mgr-solr curl registry-mgr-solr registry-mgr-solr " ]] \
  && pass "steps run in order: harvest, catalog, load history, delete, load PDS4, load PDS3" \
  || fail "unexpected order: $order"
expect_called "registry-mgr-solr -host localhost -port 8983 $PDS3_HISTORY" "history is loaded"
expect_called "registry-mgr-solr -host localhost -port 8983 $PDS4_SOLR_DOC_HOME/solr-docs" "PDS4 docs are loaded"
expect_called "registry-mgr-solr -host localhost -port 8983 $PDS3_SOLR_DOC_HOME/solr-docs" "PDS3 docs are loaded"
expect_called "data_class:Investigation" "legacy context products are deleted"
expect_called "--fail" "the delete fails on an HTTP error"
has_pds3_backup && pass "previous PDS3 docs are kept as a backup" || fail "no PDS3 backup"
[[ -f "$PDS3_SOLR_DOC_HOME/solr-docs/pds3-new.xml" ]] && pass "new PDS3 docs are in place" || fail "new PDS3 docs missing"
ls "$REGISTRY_LOG_DIR/harvest-logs"/harvest_*.txt > /dev/null 2>&1 && pass "harvest log created" || fail "no harvest log"
ls "$REGISTRY_LOG_DIR/catalog-logs"/catalog_*.txt > /dev/null 2>&1 && pass "catalog log created" || fail "no catalog log"
[[ ! -d "$PDS4_SOLR_DOC_HOME/reload_all.lock" ]] && pass "lock is released" || fail "lock left behind"

echo "TEST: prod"
setup
with_pds4_docs
run prod
expect_rc 0 "prod succeeds"
expect_not_called "^harvest-solr" "prod does not harvest"
expect_not_called "^catalog-solr" "prod does not run catalog"
expect_called "registry-mgr-solr .*$PDS4_SOLR_DOC_HOME/solr-docs" "prod loads the PDS4 docs"
[[ -f "$PDS3_SOLR_DOC_HOME/solr-docs/old.xml" ]] && pass "prod leaves the PDS3 docs alone" || fail "prod changed the PDS3 docs"

echo "TEST: harvest failure"
setup
export FAIL_HARVEST=1
run staging
expect_rc 1 "a failed harvest stops the script"
expect_out "harvest-solr failed" "the failure is reported"
expect_not_called "^registry-mgr-solr" "nothing is loaded"
expect_not_called "^catalog-solr" "catalog does not run"
[[ ! -d "$PDS4_SOLR_DOC_HOME/reload_all.lock" ]] && pass "lock is released after a failure" || fail "lock left behind"

echo "TEST: harvest that produces nothing"
setup
export EMPTY_HARVEST=1
run staging
expect_rc 1 "a harvest with no documents stops the script"
expect_out "produced no Solr documents" "the failure is reported"
expect_not_called "^registry-mgr-solr" "nothing is loaded"

echo "TEST: catalog failure"
setup
export FAIL_CATALOG=1
run staging
expect_rc 1 "a failed catalog stops the script"
[[ -f "$PDS3_SOLR_DOC_HOME/solr-docs/old.xml" ]] && pass "previous PDS3 docs are untouched" || fail "previous PDS3 docs were lost"
has_pds3_backup && fail "a backup was made for a failed run" || pass "no backup rotation on failure"
expect_not_called "^registry-mgr-solr" "nothing is loaded"

echo "TEST: loader failures"
setup
export FAIL_LOAD_MATCH="$PDS3_HISTORY"
with_pds4_docs
run prod
expect_rc 1 "a failed history load stops the script"
expect_not_called "^curl" "the delete does not run"
setup
export FAIL_LOAD_MATCH="$PDS4_SOLR_DOC_HOME/solr-docs"
with_pds4_docs
run prod
expect_rc 1 "a failed PDS4 load stops the script"
expect_not_called "$PDS3_SOLR_DOC_HOME/solr-docs" "PDS3 docs are not loaded"
expect_out "Loading the PDS4 data failed" "the failure is reported"

echo "TEST: delete failure"
setup
export FAIL_CURL=1
with_pds4_docs
run prod
expect_rc 1 "a failed delete stops the script"
expect_not_called "$PDS4_SOLR_DOC_HOME/solr-docs" "PDS4 docs are not loaded"

echo "TEST: nothing to load"
setup
rm -rf "$PDS3_SOLR_DOC_HOME/solr-docs"
with_pds4_docs
run prod
expect_rc 1 "an empty PDS3 directory stops the script"
expect_out "no Solr documents to load" "the failure is reported"

echo "TEST: lock held"
setup
mkdir "$PDS4_SOLR_DOC_HOME/reload_all.lock"
run staging
expect_rc 1 "a second reload is refused"
expect_out "Another reload" "the lock is reported"
[[ -d "$PDS4_SOLR_DOC_HOME/reload_all.lock" ]] && pass "the other reload's lock is kept" || fail "the lock was removed"
[[ ! -s "$CALLS" ]] && pass "nothing ran" || fail "tools ran while locked"

echo "TEST: Solr host and port"
setup
export SOLR_HOST=solr.example.org SOLR_PORT=9999
with_pds4_docs
run prod
expect_rc 0 "prod succeeds with another Solr"
expect_called "registry-mgr-solr -host solr.example.org -port 9999" "the loader gets the host and port"
expect_called "http://solr.example.org:9999/solr/data/update" "the delete uses the host and port"

echo
echo "Passed: $PASSED  Failed: $FAILED"
[[ $FAILED -eq 0 ]]
