#!/bin/bash

echo "Load historical PDS3 data"
export PDS3_HISTORY=/scratch/pds4/registry-solr-data/pds3/solr-docs_BASELINE/
$REGISTRY_MGR_SOLR_HOME/bin/registry-mgr-solr $PDS3_HISTORY

LOG=/scratch/pds4/registry-solr-data/harvest-logs/harvest_$(date +"%Y%m%d%H%M%S").txt
echo "Reload PDS4 data. See log file: $LOG"
HARVEST_SOLR_CONFIG=$HARVEST_SOLR_CONF_HOME/harvest-policy-reload-edwg.xml
$HARVEST_SOLR_HOME/bin/harvest-solr -c $HARVEST_SOLR_CONFIG \
				    -o $PDS4_SOLR_DOC_HOME > $LOG

$REGISTRY_MGR_SOLR_HOME/bin/registry-mgr-solr $PDS4_SOLR_DOC_HOME/solr-docs

LOG=/scratch/pds4/registry-solr-data/catalog-logs/catalog_$(date +"%Y%m%d%H%M%S").txt
echo "Reload PDS3 data. See log file: $LOG"
mv $PDS3_SOLR_DOC_HOME/solr-docs $PDS3_SOLR_DOC_HOME/solr-docs_pre$(date +"%Y%m%dT%H%M%SZ")
mkdir -p $PDS3_SOLR_DOC_HOME/solr-docs

PDS3_DATA_PATH=/home/pds4/pds3-releases/

$CATALOG_SOLR_HOME/bin/catalog-solr --mode ingest --doc-config $CATALOG_SOLR_HOME/search-conf/defaults/ --output-dir $PDS3_SOLR_DOC_HOME/solr-docs --target $PDS3_DATA_PATH > $LOG
$REGISTRY_MGR_SOLR_HOME/bin/registry-mgr-solr $PDS3_SOLR_DOC_HOME/solr-docs

echo "DONE"
