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
export DATA_HOME=/tmp/registry-data
mkdir -p $DATA_HOME

source test/setup-test-env.sh
cd test
./smoke-tests.sh
```

### Manual Setup

If you prefer to set environment variables manually:

```bash
export DATA_HOME=/tmp/registry-data
mkdir -p $DATA_HOME

export LEGACY_REGISTRY_HOME=$(pwd)/registry-mgr-legacy-4.6.0-SNAPSHOT
export LEGACY_HARVEST_HOME=$(pwd)/harvest-legacy-4.6.0-SNAPSHOT
export LEGACY_CATALOG_HOME=$(pwd)/catalog-legacy-4.6.0-SNAPSHOT

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

### Test 3: Superseded Product Versions
1. Loads two versions of one product (`src/test/resources/data/versions/`) and checks that both stay in the index while `/keyword` and `/archive-filter` return only the latest
2. Loads a newer version (`src/test/resources/data/versions-newer/`) and checks that it supersedes the previous latest
3. Loads the older versions again and checks that they are hidden again (loading drops the `superseded` flag, and `registry-mgr-solr` sets it after every load)
4. Removes the newest version and checks that the older ones stay hidden until they are loaded again

### Test 4: Context Product Names, Types And Aliases
The PDS4 harvest in Test 1 runs with a small context products file (`src/test/resources/data/context/context_products_test.json`), so no network access is needed.
1. Checks that an alias that exists only in that file finds every product that references the context product, through `/keyword` and `/archive-filter`
2. Checks that a bundle carries the context product's name, its other name and its types instead of the label's values
3. Checks that the target facets are still built, from the canonical name when it is in the hierarchy and from an alternate name when it is not

### Test 5: PDS4 Products Rank Above PDS3 Products
1. Loads two documents with the same text, one PDS4 and one PDS3 (`src/test/resources/data/model-boost/`)
2. Checks that on `/keyword`, `/search` and `/archive-filter` the PDS4 document scores at least 1.4 times the PDS3 one (the boost multiplies the PDS4 score by 1.5)

### Test 6: LID And LIDVID Queries
On `/keyword`, `/search` and `/archive-filter`, using the `insight_documents` bundle from Test 1:
1. Checks that a bundle LID, a LIDVID, a collection LID and an upper case LID each find only that product
2. Checks that a product LID or a collection LID that is not in the index finds its parents, nearest first (the collection, then the bundle, or the bundle)
3. Loads a resource that `/keyword` and `/archive-filter` hide (`src/test/resources/data/lid-search/`) and checks that those handlers treat its LID as not indexed and find its collection and bundle, while `/search` finds the resource itself
4. Checks that `<LID>*` finds the bundle first, then every product under the prefix that the handler returns, each once

## Scale Test for Superseded Versions

`versions-scale-test.sh` times `registry-mgr-solr -mark-superseded` on 180000 synthetic documents (60000 products with 3 versions each). It is a manual tool and is not part of the smoke tests or CI, because it takes a few minutes and its timings depend on the machine.

Run it against an empty, throwaway Solr (for example the one the smoke tests install); it refuses to run when the `data` collection already holds documents, and removes its documents when it finishes:

```bash
# From the repository root
export LEGACY_REGISTRY_HOME=$(echo $(pwd)/registry-mgr-legacy-*)
test/versions-scale-test.sh
```

## Test Data

- **PDS4 Test Data:** `src/test/resources/data/pds4/`
- **PDS3 Test Data:** `src/test/resources/data/pds3/`
- **Product Version Test Data:** `src/test/resources/data/versions/` and `src/test/resources/data/versions-newer/`
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
3. Clean up any existing Solr containers and data:
   ```bash
   docker rm -f registry-legacy
   docker volume rm solrdata
   rm -rf $DATA_HOME
   ```

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
