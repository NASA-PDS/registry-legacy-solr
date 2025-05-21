#!/bin/bash

check_status() {
    status=$1
    msg=$2
    if [ $status -ne 0 ]; then
        echo "ERROR: $msg"
        exit $status
    fi
}

SCRIPTDIR=$(cd "$( dirname $0 )" && pwd)
PARENTDIR=$(cd ${SCRIPTDIR}/.. && pwd)

# Check REGISTRY_MGR_LEGACY_HOME is set
if [ -z "$LEGACY_REGISTRY_HOME" ] || [ -z "$LEGACY_HARVEST_HOME" ] || [ -z "$LEGACY_CATALOG_HOME" ] || [ -z "$DATA_HOME" ]; then
  echo "ERROR: LEGACY_REGISTRY_HOME, LEGACY_HARVEST_HOME, LEGACY_CATALOG_HOME, DATA_HOME environment variables must be set for executing tools."
  exit 1
fi

OUTDIR=$DATA_HOME/test1
mkdir -p $OUTDIR

# Install Solr with preconfigured collections
#$LEGACY_REGISTRY_HOME/bin/registry_legacy_installer_docker.sh install
#check_status $? "[ERROR] Registry Manager Install Failure"

echo "+++ TEST 1 - PDS4 +++"
TEST_DATA_HOME=$PARENTDIR/src/test/resources/data/pds4

echo "[INFO] Harvest test data"
$LEGACY_HARVEST_HOME/bin/harvest-solr -c $LEGACY_HARVEST_HOME/conf/harvest/examples/harvest-policy-master.xml \
                                      -C $LEGACY_HARVEST_HOME/conf/search/defaults \
                                      -o $OUTDIR --verbose 0 \
                                      --target $TEST_DATA_HOME

check_status $? "[ERROR] Harvest Failure"
echo "[SUCCESS] Harvest Successful"

echo "[INFO] Check solr docs match expected"
SOLR_EXPECTED=$PARENTDIR/src/test/resources/data/pds4/expected/solr_doc_expected.xml
# Cleanse output file of elements that are specific to where the code is run
egrep -v "package_id|file_ref_location|file_ref_url|modification_date" $OUTDIR/solr-docs/solr_doc_0.xml > solr_doc_actual.xml

# Diff
test=$(diff solr_doc_actual.xml $SOLR_EXPECTED)
if [ -z "$test" ]; then
    echo "[SUCCESS] Harvest Solr Doc Diff Successful"
else
    echo "[ERROR] Harvest Solr Doc Diff Test Failed"
    echo $test
    exit 1
fi


# Load test data
echo "[INFO] Registry Manager Load"
$LEGACY_REGISTRY_HOME/bin/registry-mgr-solr $OUTDIR/solr-docs/

check_status $? "[ERROR] Registry Manager Load Failure"

exit

echo "+++ Test 2 - PDS3 +++"
TEST_DATA_HOME=$PARENTDIR/src/test/resources/data/test2/junosru/
#TEST_DATA_HOME=${TEST_DATA_HOME//\//\\\/}
OUTDIR=$DATA_HOME/test2
mkdir -p $OUTDIR

echo "[INFO] Parse test data with Catalog Tool"
$LEGACY_CATALOG_HOME/bin/catalog-solr --mode ingest \
                                      --doc-config $LEGACY_CATALOG_HOME/search-conf/defaults/ \
                                      --output-dir $OUTDIR --verbose 0 \
                                      --target $TEST_DATA_HOME

check_status $? "[ERROR] Catalog Solr Failure"
echo "[SUCCESS] Catalog Solr Successful"

echo "[INFO] Check solr docs match expected"
SOLR_EXPECTED=$PARENTDIR/src/test/resources/data/test2/expected/solr_doc_expected.xml
# Cleanse output file of elements that are specific to where the code is run
egrep -v "package_id|file_ref_location|file_ref_url|modification_date" $OUTDIR/usa_nasa_pds_jnosru_0xxx__jnosruedr.solr.xml > solr_doc_actual.xml

# Diff
test=$(diff solr_doc_actual.xml $SOLR_EXPECTED)
if [ -z "$test" ]; then
    echo "[SUCCESS] Catalog Solr Doc Diff Successful"
else
    echo "[ERROR] Catalog Solr Doc Diff Test Failed"
    echo $test
    exit 1
fi


# Load test data
echo "[INFO] Registry Manager Load"
$LEGACY_REGISTRY_HOME/bin/registry-mgr-solr $OUTDIR

check_status $? "[ERROR] Registry Manager Load Failure"

echo "[SUCCESS] Registry Manager Load Successful"

exit 0
