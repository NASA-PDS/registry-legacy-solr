#!/usr/bin/env bash
#
# Times registry-mgr-solr -mark-superseded on a large synthetic index.
#
# Loads 60000 products with 3 versions each (180000 documents) into a local, EMPTY Solr registry,
# then times the first pass (flags 120000 documents), a pass with nothing to change, and an
# incremental pass after 1000 products get a newer version. It also checks that search shows one
# version per product. The synthetic documents are removed when it finishes.
#
# This is a manual tool, not part of the smoke tests: it takes a few minutes and the timings depend
# on the machine. Use it on a throwaway Solr, never on a real registry. It refuses to run when the
# data collection already contains documents, unless FORCE=yes is set.
#
# Usage:
#   export LEGACY_REGISTRY_HOME=/path/to/unpacked/registry-mgr-legacy
#   ./versions-scale-test.sh

SOLR="http://localhost:8983/solr/data"
FAILED=0

if [ -z "$LEGACY_REGISTRY_HOME" ] || [ ! -x "$LEGACY_REGISTRY_HOME/bin/registry-mgr-solr" ]; then
    echo "ERROR: Set LEGACY_REGISTRY_HOME to the unpacked registry-mgr-legacy directory."
    exit 1
fi

count() {
    curl -s -G "$SOLR/$1" --data-urlencode "q=$2" --data-urlencode "wt=json" | grep -o '"numFound":[0-9]*' | head -1 | cut -d: -f2
}

check() {
    if [ "$2" == "$3" ]; then
        echo "PASS  $1"
    else
        echo "FAIL  $1 (expected '$2', got '$3')"
        FAILED=1
    fi
}

existing=$(count all "*:*")
if [ -z "$existing" ]; then
    echo "ERROR: Could not query Solr at $SOLR"
    exit 1
fi
if [ "$existing" -ne 0 ] && [ "$FORCE" != "yes" ]; then
    echo "ERROR: The data collection already holds $existing documents. Run this on an empty, throwaway Solr."
    exit 1
fi

work=$(mktemp -d)

cleanup() {
    echo "--- removing the synthetic documents"
    curl -s -X POST -H 'Content-Type: application/json' --data-binary '{"delete":{"query":"package_id:versions-scale-test"}}' "$SOLR/update?commit=true" > /dev/null
    rm -rf "$work"
}
trap cleanup EXIT

# Documents for products start..start+n-1, with versions 1.0, 1.1 and 1.2. They carry no lidvid: the
# generateLIDVID update chain builds it from lid and version_id, as it does for harvested documents.
generate() {
    awk -v s="$1" -v n="$2" 'BEGIN {
        printf "["
        first = 1
        for (i = s; i < s + n; i++) {
            for (v = 0; v < 3; v++) {
                printf "%s{\"lid\":\"urn:test:scale:p%d\",\"version_id\":\"1.%d\",\"version_id_normalized\":%s,\"package_id\":\"versions-scale-test\",\"title\":\"Scale product %d\"}", (first ? "" : ","), i, v, (v == 0 ? "1.0" : "1.0000" v), i
                first = 0
            }
        }
        printf "]"
    }'
}

echo "--- loading 60000 products x 3 versions = 180000 documents"
for start in 0 10000 20000 30000 40000 50000; do
    generate "$start" 10000 > "$work/batch.json"
    curl -s -f -X POST -H 'Content-Type: application/json' --data-binary @"$work/batch.json" "$SOLR/update" > /dev/null || { echo "ERROR: Failed to load documents"; exit 1; }
done
curl -s -f -H 'Content-Type: text/xml' --data-binary '<commit/>' "$SOLR/update" > /dev/null
check "documents loaded" 180000 "$(count all 'package_id:versions-scale-test')"

T0=$(date +%s)
"$LEGACY_REGISTRY_HOME/bin/registry-mgr-solr" -mark-superseded
T1=$(date +%s)
echo "TIME  first pass (flags 120000 documents): $((T1 - T0)) seconds"
check "one visible version per product" 60000 "$(count keyword 'package_id:versions-scale-test')"

T0=$(date +%s)
"$LEGACY_REGISTRY_HOME/bin/registry-mgr-solr" -mark-superseded
T1=$(date +%s)
echo "TIME  pass with nothing to change: $((T1 - T0)) seconds"

# A typical incremental load: 1000 products each get a newer version
awk 'BEGIN {
    printf "["
    for (i = 0; i < 1000; i++) {
        printf "%s{\"lid\":\"urn:test:scale:p%d\",\"version_id\":\"1.3\",\"version_id_normalized\":1.00003,\"package_id\":\"versions-scale-test\",\"title\":\"Scale product %d\"}", (i ? "," : ""), i, i
    }
    printf "]"
}' > "$work/batch.json"
curl -s -f -X POST -H 'Content-Type: application/json' --data-binary @"$work/batch.json" "$SOLR/update?commit=true" > /dev/null || { echo "ERROR: Failed to load documents"; exit 1; }

T0=$(date +%s)
"$LEGACY_REGISTRY_HOME/bin/registry-mgr-solr" -mark-superseded
T1=$(date +%s)
echo "TIME  incremental pass (1000 new versions): $((T1 - T0)) seconds"
check "still one visible version per product" 60000 "$(count keyword 'package_id:versions-scale-test')"

echo
if [ "$FAILED" -eq 0 ]; then
    echo "ALL CHECKS PASSED"
else
    echo "SOME CHECKS FAILED"
fi
exit "$FAILED"
