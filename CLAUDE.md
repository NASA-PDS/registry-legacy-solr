# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Registry Legacy Solr is a multi-module Maven project that supports the PDS Legacy Solr Registry system, which drives the PDS Keyword Search and Tool Registry. This is a NASA PDS (Planetary Data System) project using Apache Solr for data indexing and search.

### Architecture

This is a **multi-module Maven project** with four core modules:

1. **search-core** - Shared search configuration and utilities used by other modules
2. **harvest-legacy** - Crawls PDS4 product metadata and generates Solr-readable documents
3. **catalog-legacy** - Processes PDS3 catalog objects using pds3-product-tools library
4. **registry-mgr-legacy** - Manages Solr Registry: installation, deployment, and document loading

The modules have dependencies: harvest-legacy and catalog-legacy depend on search-core-legacy.

### Key Technologies

- **OpenJDK 17 or higher** (target/source in pom.xml)
- **Maven** for build management
- **Apache Solr 9.10.0** for search/indexing
- **PDS4/PDS3 XML** for metadata extraction
- **Docker** for Solr deployment

### Prerequisites

- **OpenJDK 17 or higher** - Required for building and running all components
- **Maven 3.x** - For building the project
- **Docker** - For Solr deployment (production environments)

## Production Deployment and Usage

### Live Deployments

This Solr registry powers multiple production PDS services:

- **Solr Registry API**: https://pds.nasa.gov/services/search/search
- **PDS Keyword Search**: https://pds.nasa.gov/datasearch/keyword-search/
- **Future PDS Portal**: https://pds.mcp.nasa.gov/portal/

### Related Repositories

- **search-ui-legacy** (https://github.com/NASA-PDS/search-ui-legacy) - Pass-through API layer and keyword search UI that sits on top of this Solr registry
- **ds-view** (https://github.com/NASA-PDS/ds-view) - Dataset landing pages driven by this registry

### Infrastructure

**Production Environments:**
- **pdscloud-gamma** - Staging environment for testing data loads
- **pdscloud-prod1** - Production instance 1
- **pdscloud-prod2** - Production instance 2

**User Accounts:**
- `pds4` - Main account for running harvest and data operations
- `pdsops` - Account for Docker/Solr operations
- `solrdock` - Docker user namespace for Solr container

**Critical Constraint:** Only **ONE harvest can run at a time** on any machine (resource intensive operation)

### Operational Data Flow

The typical production workflow:

1. **Staging (gamma)**: Run harvest to generate Solr docs → Load into gamma Solr → Verify via keyword search
2. **Production**: Use the same Solr docs generated on gamma and load into prod1/prod2 Solr

```bash
# On pdscloud-gamma (staging)
ssh pds4@pdscloud-gamma
nohup $HARVEST_SOLR_HOME/bin/harvest-solr --harvest-config $HARVEST_SOLR_CONFIG \
    --output-dir $PDS4_SOLR_DOC_HOME \
    --target $DATA_PATH \
    > /scratch/pds4/registry-solr-data/harvest-logs/harvest_$(date +"%Y%m%d%H%M%S").txt 2>&1&

$REGISTRY_MGR_SOLR_HOME/bin/registry-mgr-solr $PDS4_SOLR_DOC_HOME/solr-docs

# After verifying on gamma, load same docs to prod
ssh pdscloud-prod1
$REGISTRY_MGR_SOLR_HOME/bin/registry-mgr-solr $PDS4_SOLR_DOC_HOME/solr-docs
```

### Data Types and Harvest Configurations

Multiple harvest configurations exist for different data types:

| Data Type | Config Path | Description |
|-----------|-------------|-------------|
| PDS4 Data Releases | `harvest-policy-releases.xml` | Main PDS4 data from `/data/pds4/releases/` |
| Context Products | `harvest-policy-context.xml` | PDS3/PDS4 context products |
| System Bundle | `harvest-policy-system.xml` | System bundle data |
| Legacy Documents | `harvest-policy-documents.xml` | Legacy documentation |
| SIP Manifests | `harvest-policy-manifests.xml` | Submission manifests |
| IPDA (PSA Data) | `harvest-policy-ipda.xml` | ESA Planetary Science Archive data |

### Performance Metrics

- **Harvest**: ~0.6 seconds per label (~10 minutes for 1000 labels)
- **Registry Manager**: ~11 seconds for ~1000 products
- **Example**: 6238 labels takes ~62 minutes to harvest

### Common Production Operations

**Delete entire package:**
```bash
PACKAGE_ID=<package-id-from-solr>
curl -X POST -H 'Content-Type: application/json' --data-binary \
  '{"delete":{"query":"package_id:\"'$PACKAGE_ID'\"" }}' \
  'http://localhost:8983/solr/data/update?commit=true'
```

**Delete specific PDS4 product:**
```bash
LID=urn:nasa:pds:context:instrument_host:spacecraft.a17s
curl -X POST -H 'Content-Type: application/json' --data-binary \
  '{"delete":{"query":"lid:\"'$LID'\"" }}' \
  'http://localhost:8983/solr/data/update?commit=true'
```

**Supersede PDS3 dataset:**
```bash
DATA_SET_ID=JNO-J-SRU-COUNTRATE-TABLE-5-L2-V1.0
curl -X POST -H 'Content-Type: application/json' --data-binary \
  '{"delete":{"query":"data_set_id:\"'$DATA_SET_ID'\"" }}' \
  'http://localhost:8983/solr/data/update?commit=true'
```

**Check Solr status:**
```bash
curl http://localhost:8983/solr/admin/cores?action=STATUS
```

**Restart Docker container (as pdsops):**
```bash
docker ps -a  # Check container status
docker start <container_id>  # Restart if stopped
```

### Environment Variables (Production)

Key environment variables used in deployment scripts:

- `HARVEST_SOLR_HOME` - Harvest installation directory
- `HARVEST_SOLR_CONF_HOME` - Harvest configuration directory (usually `~/harvest-config/`)
- `REGISTRY_MGR_SOLR_HOME` - Registry Manager installation directory
- `CATALOG_SOLR_HOME` - Catalog tool installation directory
- `PDS4_SOLR_DOC_HOME` - Output directory for PDS4 Solr documents (shared between staging and prod)
- `PDS3_SOLR_DOC_HOME` - Output directory for PDS3 Solr documents
- `JAVA_HOME` - OpenJDK 17 or higher installation (e.g., `/data/home/pds4/jvm/jdk-17/`)

### Docker Configuration Notes

Production uses Docker user namespace remapping for security:
- Docker configured with `"userns-remap": "1006:1000"` (pds4 user)
- Solr runs as user `8983` (Solr's standard UID)
- Data volumes owned by `solrdock` user (UID 500000)

## Build and Test Commands

### Building

```bash
# Compile only
mvn compile

# Create JAR files
mvn compile jar:jar

# Full build with tests and packaging
mvn install

# Build and run tests
mvn test

# Build without tests
mvn install -DskipTests

# Clean build artifacts
mvn clean
```

### Running Tests

The repository uses integration tests rather than traditional unit tests:

```bash
# Run all tests (includes smoke tests via mvn install)
mvn install

# Run smoke tests manually (requires environment setup)
# First, build and unpack distributions
mvn install
tar -xzf registry-mgr-legacy/target/registry-mgr-legacy-*-bin.tar.gz
tar -xzf harvest-legacy/target/harvest-legacy-*-bin.tar.gz
tar -xzf catalog-legacy/target/catalog-legacy-*-bin.tar.gz

# Set up environment and run tests
source test/setup-test-env.sh
cd test
./smoke-tests.sh
```

The smoke tests (`test/smoke-tests.sh`):
1. Install Solr with Docker using registry_legacy_installer_docker.sh
2. Harvest PDS4 test data and compare against expected Solr documents
3. Process PDS3 catalog data and compare against expected output
4. Load documents into Solr via registry-mgr-solr
5. Query Solr to validate data was loaded correctly

Test data locations:
- PDS4: `src/test/resources/data/pds4/`
- PDS3: `src/test/resources/data/pds3/`
- Expected outputs: `src/test/resources/data/expected/`

See `test/README.md` for detailed testing documentation and troubleshooting.

### Documentation

```bash
# Generate and serve documentation locally
mvn site:run
# View at http://localhost:8080

# Generate site without serving
mvn site
```

## Module Entry Points and Commands

### Harvest Legacy (PDS4)
Main class: `gov.nasa.pds.harvest.search.HarvestSearchLauncher`

```bash
# After building, unpack distribution:
tar -xvzf harvest-legacy/target/harvest-legacy-*-bin.tar.gz
cd harvest-legacy-*/

# Run harvest (crawl and index PDS4 metadata)
bin/harvest-solr -c conf/harvest/examples/harvest-policy-master.xml \
                 -C conf/search/defaults \
                 -o /output/dir \
                 --target /path/to/pds4/data
```

### Registry Manager Legacy
Scripts in `registry-mgr-legacy/src/main/resources/bin/`:

```bash
# Install Solr with Docker
bin/registry_legacy_installer_docker.sh install

# Install Solr standalone
bin/registry_legacy_installer_standalone.sh

# Load Solr documents
bin/registry-mgr-solr /path/to/solr-docs/
```

### Catalog Legacy (PDS3)

```bash
# Process PDS3 catalog objects
bin/catalog-solr --mode ingest \
                 --doc-config conf/search/defaults/ \
                 --output-dir /output/dir \
                 --target /path/to/pds3/data
```

## Adding New Search Attributes

To add new searchable fields to the system (as documented in README.md):

1. **Update Harvest Global Policy** - `harvest-legacy/src/main/resources/policy/global-policy.xml`
   - Add XPath expressions to extract new attributes from PDS4 XML labels

2. **Update Master Config** - `harvest-legacy/src/main/resources/conf/harvest/examples/harvest-policy-master.xml`
   - Configure harvesting for additional product types

3. **Update Search Core Config** - `harvest-legacy/src/main/resources/conf/search/defaults/pds/pds4/`
   - Map harvested attributes to Solr field names

4. **Update Solr Schema** - `registry-mgr-legacy/src/main/resources/collections/data/managed-schema.xml`
   - Define new field types in Solr schema (e.g., `<field name="new_field" type="text_general" .../>`)

5. **Redeploy and Reindex** - Changes require redeploying Solr and reindexing all data

## Project Structure Notes

### Configuration Files
- **Harvest policies**: Define what metadata to extract from PDS XML labels
  - Global: `harvest-legacy/src/main/resources/policy/global-policy.xml`
  - Examples: `harvest-legacy/src/main/resources/conf/harvest/examples/`

- **Search configs**: Map extracted metadata to Solr fields
  - `harvest-legacy/src/main/resources/conf/search/defaults/`
  - Mission-specific configs in subdirectories (e.g., `maven/maven-ngims/`)

- **Solr schemas**: Define Solr field types and indexing behavior
  - Data collection: `registry-mgr-legacy/src/main/resources/collections/data/managed-schema.xml`
  - Registry collection: `registry-mgr-legacy/src/main/resources/collections/registry/managed-schema.xml`

### Package Structure
- Harvest: `gov.nasa.pds.harvest.search.*`
- Catalog: `gov.nasa.pds.citool.*`
- Registry Manager: `gov.nasa.pds.search.*`

## CI/CD

GitHub Actions workflows in `.github/workflows/`:

- **branch-cicd.yaml** - Runs on all branches except main; executes `mvn install` and smoke tests
- **stable-cicd.yaml** - Triggers on `release/x.x.x` tags for releases
- **unstable-cicd.yaml** - Development builds
- **secrets-detection.yaml** - Security scanning
- **codeql-analysis.yml** - Code quality analysis

The branch CI builds artifacts, unpacks them, and runs smoke tests in Docker.

## Release Process

Operational releases are created by:
1. Creating a tag `release/x.x.x`
2. The stable-cicd.yaml workflow executes and tags the software
3. Artifacts are deployed to Maven Central

Main branch: `main`

## Important Dependencies

- **jaxb-runtime 2.3.5** - WARNING: Upgrading to 4.x breaks Harvest (see harvest-legacy/pom.xml:181)
- **pds3-product-tools 4.4.0** - Used by catalog-legacy for PDS3 parsing
- **Apache Solr 9.8.0** - Core search platform
- **Spring Framework 6.x** - Used for dependency injection

## Working with This Codebase

- This project requires **OpenJDK 17 or higher** - ensure your `JAVA_HOME` is set correctly
- The system processes planetary science data in NASA PDS3 and PDS4 formats
- Solr configuration is critical - schema changes require full reindexing
- Test data is included in `src/test/resources/` for validation
- Built artifacts are packaged as `.tar.gz` and `.zip` distributions with bin scripts included
