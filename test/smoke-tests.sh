#!/bin/bash
#
# Improved Smoke Tests for Registry Legacy Solr
# Tests end-to-end execution: harvest -> solr doc generation -> solr loading
#

set -o pipefail  # Fail on pipe errors

# Color output for better readability
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check command exit status
check_status() {
    local status=$1
    local msg=$2
    if [ $status -ne 0 ]; then
        log_error "$msg"
        cleanup_and_exit $status
    fi
}

# Cleanup function
cleanup_and_exit() {
    local exit_code=${1:-0}

    log_info "Cleaning up temporary files..."
    if [ -n "$TEMP_DIR" ] && [ -d "$TEMP_DIR" ]; then
        rm -rf "$TEMP_DIR"
    fi

    if [ $exit_code -eq 0 ]; then
        log_success "All smoke tests passed!"
    else
        log_error "Smoke tests failed with exit code $exit_code"
    fi

    exit $exit_code
}

# Set up signal handlers for cleanup
trap 'cleanup_and_exit 130' INT TERM

# ============================================================================
# Setup and Validation
# ============================================================================

SCRIPTDIR=$(cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd)
PARENTDIR=$(cd "${SCRIPTDIR}/.." && pwd)

# Create temporary directory for test outputs
TEMP_DIR=$(mktemp -d -t smoke-test.XXXXXX)
log_info "Using temporary directory: $TEMP_DIR"

# Validate required environment variables
REQUIRED_VARS=(
    "LEGACY_REGISTRY_HOME"
    "LEGACY_HARVEST_HOME"
    "LEGACY_CATALOG_HOME"
    "DATA_HOME"
)

for var in "${REQUIRED_VARS[@]}"; do
    if [ -z "${!var}" ]; then
        log_error "Environment variable $var is not set"
        log_error "Required variables: ${REQUIRED_VARS[*]}"
        exit 1
    fi
done

log_success "All required environment variables are set"

# Validate executables exist
if [ ! -f "$LEGACY_REGISTRY_HOME/bin/registry_legacy_installer_docker.sh" ]; then
    log_error "Registry installer not found at: $LEGACY_REGISTRY_HOME/bin/registry_legacy_installer_docker.sh"
    exit 1
fi

if [ ! -f "$LEGACY_HARVEST_HOME/bin/harvest-solr" ]; then
    log_error "Harvest executable not found at: $LEGACY_HARVEST_HOME/bin/harvest-solr"
    exit 1
fi

if [ ! -f "$LEGACY_CATALOG_HOME/bin/catalog-solr" ]; then
    log_error "Catalog executable not found at: $LEGACY_CATALOG_HOME/bin/catalog-solr"
    exit 1
fi

log_success "All required executables found"

# ============================================================================
# Helper Functions
# ============================================================================

# Normalize XML for comparison (remove dynamic fields)
normalize_solr_doc() {
    local input_file=$1
    local output_file=$2

    if [ ! -f "$input_file" ]; then
        log_error "Input file not found: $input_file"
        return 1
    fi

    # Remove fields that vary between runs
    # - package_id: Generated UUID
    # - file_ref_location: Absolute path
    # - file_ref_url: Absolute URL
    # - modification_date: Current timestamp
    grep -Ev "package_id|file_ref_location|file_ref_url|modification_date" "$input_file" > "$output_file"

    return $?
}

# Compare XML files with better diff output
compare_xml_files() {
    local actual=$1
    local expected=$2
    local test_name=$3

    if ! diff -u "$expected" "$actual" > "${TEMP_DIR}/diff_output.txt" 2>&1; then
        log_error "$test_name: Generated Solr document differs from expected"
        log_info "Showing first 50 lines of diff:"
        head -50 "${TEMP_DIR}/diff_output.txt"
        log_info "Full diff saved to: ${TEMP_DIR}/diff_output.txt"
        return 1
    fi

    log_success "$test_name: Generated Solr document matches expected output"
    return 0
}

# Query Solr to validate data was loaded
query_solr() {
    local query=$1
    local expected_count=$2
    local test_name=$3

    log_info "$test_name: Querying Solr: $query"

    local response=$(curl -s "http://localhost:8983/solr/data/select?q=${query}&wt=json")
    local actual_count=$(echo "$response" | grep -o '"numFound":[0-9]*' | cut -d':' -f2)

    if [ -z "$actual_count" ]; then
        log_error "$test_name: Failed to query Solr or parse response"
        return 1
    fi

    if [ "$actual_count" -lt "$expected_count" ]; then
        log_error "$test_name: Expected at least $expected_count documents, found $actual_count"
        return 1
    fi

    log_success "$test_name: Found $actual_count documents in Solr (expected >= $expected_count)"
    return 0
}

# Find generated Solr doc files (handles variable filenames)
find_solr_doc() {
    local output_dir=$1
    local pattern=${2:-"*.xml"}

    # Find first matching XML file in solr-docs directory
    local solr_doc=$(find "$output_dir" -name "$pattern" -type f | head -1)

    if [ -z "$solr_doc" ]; then
        log_error "No Solr document found matching pattern: $pattern in $output_dir"
        return 1
    fi

    echo "$solr_doc"
    return 0
}

# ============================================================================
# Solr Installation
# ============================================================================

log_info "==============================================="
log_info "Installing Solr with Docker"
log_info "==============================================="

"$LEGACY_REGISTRY_HOME/bin/registry_legacy_installer_docker.sh" install
check_status $? "Registry Manager installation failed"

log_success "Solr installation completed"

# Wait for Solr to be fully ready
log_info "Waiting for Solr to be ready..."
for i in {1..30}; do
    if curl -s "http://localhost:8983/solr/admin/cores?action=STATUS" > /dev/null 2>&1; then
        log_success "Solr is ready"
        break
    fi
    if [ $i -eq 30 ]; then
        log_error "Solr failed to start within 30 seconds"
        cleanup_and_exit 1
    fi
    sleep 1
done

# ============================================================================
# TEST 1: PDS4 Harvest and Load
# ============================================================================

log_info "==============================================="
log_info "TEST 1: PDS4 Data Processing"
log_info "==============================================="

PDS4_TEST_DATA="$PARENTDIR/src/test/resources/data/pds4"
PDS4_OUTDIR="$DATA_HOME/test/pds4"
PDS4_EXPECTED="$PARENTDIR/src/test/resources/data/expected/pds4/solr_doc_expected.xml"

mkdir -p "$PDS4_OUTDIR"

# Run harvest
log_info "Running PDS4 harvest..."
"$LEGACY_HARVEST_HOME/bin/harvest-solr" \
    -c "$LEGACY_HARVEST_HOME/conf/harvest/examples/harvest-policy-master.xml" \
    -C "$LEGACY_HARVEST_HOME/conf/search/defaults" \
    -o "$PDS4_OUTDIR" \
    --verbose 0 \
    --target "$PDS4_TEST_DATA"

check_status $? "PDS4 harvest failed"
log_success "PDS4 harvest completed"

# Verify Solr documents were generated
if [ ! -d "$PDS4_OUTDIR/solr-docs" ]; then
    log_error "Solr docs directory not created: $PDS4_OUTDIR/solr-docs"
    cleanup_and_exit 1
fi

# Find the generated Solr document
PDS4_SOLR_DOC=$(find_solr_doc "$PDS4_OUTDIR/solr-docs" "solr_doc_*.xml")
check_status $? "Failed to find generated PDS4 Solr document"
log_info "Found generated Solr document: $PDS4_SOLR_DOC"

# Normalize and compare
normalize_solr_doc "$PDS4_SOLR_DOC" "${TEMP_DIR}/pds4_actual.xml"
check_status $? "Failed to normalize PDS4 Solr document"

compare_xml_files "${TEMP_DIR}/pds4_actual.xml" "$PDS4_EXPECTED" "PDS4 Document Comparison"
check_status $? "PDS4 document comparison failed"

# Load into Solr
log_info "Loading PDS4 data into Solr..."
"$LEGACY_REGISTRY_HOME/bin/registry-mgr-solr" "$PDS4_OUTDIR/solr-docs/"
check_status $? "Failed to load PDS4 data into Solr"
log_success "PDS4 data loaded into Solr"

# Validate data in Solr
sleep 2  # Give Solr time to commit
query_solr "*:*" 1 "PDS4 Solr Validation"
check_status $? "PDS4 Solr validation failed"

# ============================================================================
# TEST 2: PDS3 Catalog and Load
# ============================================================================

log_info "==============================================="
log_info "TEST 2: PDS3 Data Processing"
log_info "==============================================="

PDS3_TEST_DATA="$PARENTDIR/src/test/resources/data/pds3/"
PDS3_OUTDIR="$DATA_HOME/test/pds3"
PDS3_EXPECTED="$PARENTDIR/src/test/resources/data/expected/pds3/solr_doc_expected.xml"

mkdir -p "$PDS3_OUTDIR"

# Run catalog tool
log_info "Running PDS3 catalog tool..."
"$LEGACY_CATALOG_HOME/bin/catalog-solr" \
    --mode ingest \
    --doc-config "$LEGACY_CATALOG_HOME/search-conf/defaults/" \
    --output-dir "$PDS3_OUTDIR" \
    --verbose 0 \
    --target "$PDS3_TEST_DATA"

check_status $? "PDS3 catalog tool failed"
log_success "PDS3 catalog processing completed"

# Find the generated Solr document (PDS3 uses different naming)
PDS3_SOLR_DOC=$(find_solr_doc "$PDS3_OUTDIR" "*.solr.xml")
check_status $? "Failed to find generated PDS3 Solr document"
log_info "Found generated Solr document: $PDS3_SOLR_DOC"

# Normalize and compare
normalize_solr_doc "$PDS3_SOLR_DOC" "${TEMP_DIR}/pds3_actual.xml"
check_status $? "Failed to normalize PDS3 Solr document"

compare_xml_files "${TEMP_DIR}/pds3_actual.xml" "$PDS3_EXPECTED" "PDS3 Document Comparison"
check_status $? "PDS3 document comparison failed"

# Load into Solr
log_info "Loading PDS3 data into Solr..."
"$LEGACY_REGISTRY_HOME/bin/registry-mgr-solr" "$PDS3_OUTDIR"
check_status $? "Failed to load PDS3 data into Solr"
log_success "PDS3 data loaded into Solr"

# Validate data in Solr (should have both PDS4 and PDS3 data now)
sleep 2  # Give Solr time to commit
query_solr "*:*" 2 "PDS3 Solr Validation"
check_status $? "PDS3 Solr validation failed"

# ============================================================================
# Final Summary
# ============================================================================

log_info "==============================================="
log_info "Test Summary"
log_info "==============================================="
log_success "✓ PDS4 harvest and document generation"
log_success "✓ PDS4 document validation"
log_success "✓ PDS4 Solr loading and verification"
log_success "✓ PDS3 catalog processing and document generation"
log_success "✓ PDS3 document validation"
log_success "✓ PDS3 Solr loading and verification"

cleanup_and_exit 0
