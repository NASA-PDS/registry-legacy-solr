#!/bin/bash

# Check if environment argument is provided
if [ $# -ne 1 ]; then
    echo "Usage: $0 [staging|prod]"
    exit 1
fi

ENV=$1
if [ "$ENV" != "staging" ] && [ "$ENV" != "prod" ]; then
    echo "Error: Environment must be either 'staging' or 'prod'"
    exit 1
fi

# Only execute harvest-solr and catalog-solr for staging environment
if [ "$ENV" = "staging" ]; then
  LOG=/scratch/pds4/registry-solr-data/harvest-logs/harvest_$(date +"%Y%m%d%H%M%S").txt
  echo "Processing PDS4 data (harvest-solr). See log file: $LOG"
  HARVEST_SOLR_CONFIG=$HARVEST_SOLR_CONF_HOME/harvest-policy-reload-all.xml
  $HARVEST_SOLR_HOME/bin/harvest-solr -c $HARVEST_SOLR_CONFIG \
                      -o $PDS4_SOLR_DOC_HOME > $LOG

  # Process PDS3 data
  LOG=/scratch/pds4/registry-solr-data/catalog-logs/catalog_$(date +"%Y%m%d%H%M%S").txt
  echo "Processing PDS3 data (catalog-solr). See log file: $LOG"

  # Backup existing solr-docs directory
  mv $PDS3_SOLR_DOC_HOME/solr-docs $PDS3_SOLR_DOC_HOME/solr-docs_pre$(date +"%Y%m%dT%H%M%SZ")
  mkdir -p $PDS3_SOLR_DOC_HOME/solr-docs

  # Set PDS3 data path and process data
  PDS3_DATA_PATH=/home/pds4/pds3-releases/

  $CATALOG_SOLR_HOME/bin/catalog-solr --mode ingest --doc-config $CATALOG_SOLR_HOME/search-conf/defaults/ --output-dir $PDS3_SOLR_DOC_HOME/solr-docs --target $PDS3_DATA_PATH > $LOG
fi

# Load historical PDS3 data into Solr
echo "Loading historical PDS3 data..."
export PDS3_HISTORY=/scratch/pds4/registry-solr-data/pds3/solr-docs_BASELINE/
$REGISTRY_MGR_SOLR_HOME/bin/registry-mgr-solr $PDS3_HISTORY

# Reload PDS4 data
echo "Loading PDS4 data..."
$REGISTRY_MGR_SOLR_HOME/bin/registry-mgr-solr $PDS4_SOLR_DOC_HOME/solr-docs

# Reload PDS3 data
echo "Loading PDS3 data..."
$REGISTRY_MGR_SOLR_HOME/bin/registry-mgr-solr $PDS3_SOLR_DOC_HOME/solr-docs

echo "DONE"
