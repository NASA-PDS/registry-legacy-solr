#!/bin/bash
#
# Setup environment for running smoke tests
# Source this file before running smoke tests:
#   source test/setup-test-env.sh
#

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

# Set up DATA_HOME for test outputs
export DATA_HOME=${DATA_HOME:-/tmp/registry-data}
mkdir -p $DATA_HOME

# Find and set the unpacked distribution directories
# These use glob patterns that the shell will expand
export LEGACY_REGISTRY_HOME=$(echo $REPO_ROOT/registry-mgr-legacy-*-SNAPSHOT | head -1)
export LEGACY_HARVEST_HOME=$(echo $REPO_ROOT/harvest-legacy-*-SNAPSHOT | head -1)
export LEGACY_CATALOG_HOME=$(echo $REPO_ROOT/catalog-legacy-*-SNAPSHOT | head -1)

# Verify the directories exist
if [ ! -d "$LEGACY_REGISTRY_HOME" ]; then
    echo "ERROR: LEGACY_REGISTRY_HOME not found: $LEGACY_REGISTRY_HOME"
    echo "Did you run 'mvn install' and unpack the distributions?"
    echo ""
    echo "Run these commands first:"
    echo "  mvn install"
    echo "  tar -xzf registry-mgr-legacy/target/registry-mgr-legacy-*-bin.tar.gz"
    echo "  tar -xzf harvest-legacy/target/harvest-legacy-*-bin.tar.gz"
    echo "  tar -xzf catalog-legacy/target/catalog-legacy-*-bin.tar.gz"
    return 1
fi

if [ ! -d "$LEGACY_HARVEST_HOME" ]; then
    echo "ERROR: LEGACY_HARVEST_HOME not found: $LEGACY_HARVEST_HOME"
    return 1
fi

if [ ! -d "$LEGACY_CATALOG_HOME" ]; then
    echo "ERROR: LEGACY_CATALOG_HOME not found: $LEGACY_CATALOG_HOME"
    return 1
fi

echo "Environment configured successfully:"
echo "  LEGACY_REGISTRY_HOME: $LEGACY_REGISTRY_HOME"
echo "  LEGACY_HARVEST_HOME:  $LEGACY_HARVEST_HOME"
echo "  LEGACY_CATALOG_HOME:  $LEGACY_CATALOG_HOME"
echo "  DATA_HOME:            $DATA_HOME"
echo ""
echo "You can now run smoke tests:"
echo "  cd test && ./smoke-tests.sh"
