# Smoke Tests

This directory contains end-to-end smoke tests for Registry Legacy Solr components.

## Prerequisites

1. **Build and unpack distributions:**
   ```bash
   mvn install
   tar -xzf registry-mgr-legacy/target/registry-mgr-legacy-*-bin.tar.gz
   tar -xzf harvest-legacy/target/harvest-legacy-*-bin.tar.gz
   tar -xzf catalog-legacy/target/catalog-legacy-*-bin.tar.gz
   ```

2. **Docker must be running** (for Solr container)

3. **OpenJDK 17 or higher** must be installed

## Running Smoke Tests

### Quick Start

```bash
# From repository root
source test/setup-test-env.sh
cd test
./smoke-tests.sh
```

### Manual Setup

If you prefer to set environment variables manually:

```bash
export LEGACY_REGISTRY_HOME=$(pwd)/registry-mgr-legacy-4.6.0-SNAPSHOT
export LEGACY_HARVEST_HOME=$(pwd)/harvest-legacy-4.6.0-SNAPSHOT
export LEGACY_CATALOG_HOME=$(pwd)/catalog-legacy-4.6.0-SNAPSHOT
export DATA_HOME=/tmp/registry-data

cd test
./smoke-tests.sh
```

## Test Scripts

### `smoke-tests.sh`

End-to-end smoke test script with:
- ✅ Better error handling and reporting
- ✅ Dynamic file discovery (no hardcoded filenames)
- ✅ Automatic cleanup of temporary files
- ✅ Solr validation queries to verify data loading
- ✅ Color-coded output for easy reading
- ✅ Proper signal handling and cleanup on exit

### `setup-test-env.sh`

Helper script to set up environment variables. Source this before running tests:
```bash
source test/setup-test-env.sh
```

This script automatically finds the unpacked distribution directories and sets all required environment variables.

## What the Tests Do

### Test 1: PDS4 Processing
1. Runs harvest on PDS4 test data (`src/test/resources/data/pds4/`)
2. Generates Solr XML documents
3. Compares generated docs against expected output
4. Loads documents into Solr
5. Queries Solr to verify data was loaded

### Test 2: PDS3 Processing
1. Runs catalog tool on PDS3 test data (`src/test/resources/data/pds3/`)
2. Generates Solr XML documents
3. Compares generated docs against expected output
4. Loads documents into Solr
5. Queries Solr to verify data was loaded

## Test Data

- **PDS4 Test Data:** `src/test/resources/data/pds4/`
- **PDS3 Test Data:** `src/test/resources/data/pds3/`
- **Expected Outputs:** `src/test/resources/data/expected/`

## Troubleshooting

### "Registry installer not found"

Make sure you've unpacked the distributions and the environment variables are set correctly:
```bash
source test/setup-test-env.sh
```

### "Solr failed to start"

1. Check Docker is running: `docker ps`
2. Check if port 8983 is already in use: `lsof -i :8983`
3. If Solr container exists, remove it: `docker rm -f registry`

### "No Solr document found"

This means harvest/catalog didn't generate the expected output files. Check:
1. The tool ran successfully (no errors in output)
2. The output directory exists: `ls -la $DATA_HOME/test/pds4/solr-docs/`
3. Test data exists: `ls -la src/test/resources/data/pds4/`

### Environment variable has `*` in path

The glob pattern wasn't expanded. The easiest fix is to use the setup script:
```bash
source test/setup-test-env.sh
```

Or manually expand the glob:
```bash
export LEGACY_REGISTRY_HOME=$(echo $(pwd)/registry-mgr-legacy-*)
# NOT: export LEGACY_REGISTRY_HOME=$(pwd)/registry-mgr-legacy-*
```

## CI/CD Integration

These tests run in GitHub Actions via `.github/workflows/branch-cicd.yaml`.

See the workflow file for the exact commands used in CI.

## Future Testing Improvements

See `TESTING_RECOMMENDATIONS.md` for recommendations on migrating to:
- JUnit + Testcontainers for better integration testing
- Unit tests for harvest/catalog logic
- Schema validation tests
