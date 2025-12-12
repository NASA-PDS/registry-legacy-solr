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
# Docker and Volume Diagnostics
# ============================================================================

log_info "==============================================="
log_info "Pre-Installation Docker Diagnostics"
log_info "==============================================="

# Check Docker is running
if ! docker info > /dev/null 2>&1; then
    log_error "Docker is not running or not accessible"
    log_info "Try: sudo systemctl start docker (Linux) or start Docker Desktop (Mac)"
    cleanup_and_exit 1
fi
log_success "Docker is running"

# Show Docker version
log_info "Docker version: $(docker --version)"

# Check if DATA_HOME exists and show its permissions
log_info "DATA_HOME: $DATA_HOME"
if [ ! -d "$DATA_HOME" ]; then
    log_warning "DATA_HOME does not exist, creating it..."
    mkdir -p "$DATA_HOME"
fi

log_info "DATA_HOME permissions before setup:"
ls -ld "$DATA_HOME"

# Note: The installer script will automatically handle permissions
# It detects CI environments and uses sudo chown when necessary
if [ -n "$CI" ] || [ -n "$GITHUB_ACTIONS" ]; then
    log_info "Running in CI environment - installer will automatically set permissions for Solr (UID 8983)"
fi

# ============================================================================
# Solr Installation
# ============================================================================

log_info "==============================================="
log_info "Installing Solr with Docker"
log_info "==============================================="

"$LEGACY_REGISTRY_HOME/bin/registry_legacy_installer_docker.sh" install

# Check if installation failed
if [ $? -ne 0 ]; then
    log_error "Registry Manager installation failed"

    # Show detailed diagnostics
    log_info "=== Docker Container Status ==="
    docker ps -a | grep -i registry || log_warning "No registry container found"

    log_info "=== Docker Logs (last 50 lines) ==="
    docker logs registry-legacy 2>&1 | tail -50 || log_warning "Could not get container logs"

    log_info "=== Docker Volume Inspection ==="
    docker volume ls | grep solrdata || log_warning "No solrdata volume found"
    docker volume inspect solrdata 2>&1 || log_warning "Could not inspect volume"

    log_info "=== DATA_HOME Directory Contents ==="
    ls -laR "$DATA_HOME" || log_warning "Could not list DATA_HOME"

    cleanup_and_exit 1
fi

log_success "Solr installation completed"

# ============================================================================
# Verify Docker Volume Mount
# ============================================================================

log_info "==============================================="
log_info "Verifying Docker Volume Mount"
log_info "==============================================="

# Check container is running
if ! docker ps | grep -q registry-legacy; then
    log_error "Registry container is not running"
    log_info "Container status:"
    docker ps -a | grep registry-legacy
    log_info "Container logs:"
    docker logs registry-legacy 2>&1 | tail -50
    cleanup_and_exit 1
fi

log_success "Registry container is running"

# Verify volume mount
log_info "Checking volume mount inside container..."
MOUNT_CHECK=$(docker exec registry-legacy df -h /var/solr 2>&1)
if [ $? -eq 0 ]; then
    log_success "Volume is mounted:"
    echo "$MOUNT_CHECK" | grep -E "(Filesystem|solr)"
else
    log_error "Could not verify volume mount"
fi

# Test write access inside container
log_info "Testing write access inside container..."
if docker exec --user=solr registry-legacy touch /var/solr/test-write-access 2>&1; then
    log_success "Solr user can write to /var/solr"
    docker exec --user=solr registry-legacy rm /var/solr/test-write-access 2>/dev/null
else
    log_error "Solr user CANNOT write to /var/solr - permission denied"
    log_info "This is likely the cause of Solr startup failure"

    # Show what we can see
    log_info "Directory listing inside container:"
    docker exec registry-legacy ls -la /var/solr 2>&1 || true

    log_info "Permissions on host:"
    ls -la "$DATA_HOME/solrdata/" 2>&1 || true

    cleanup_and_exit 1
fi

# Verify solrdata directory exists on host
log_info "Verifying solrdata directory on host..."
if [ -d "$DATA_HOME/solrdata" ]; then
    log_success "solrdata directory exists on host"
    log_info "Permissions:"
    ls -ld "$DATA_HOME/solrdata"

    # Check if data directory exists
    if [ -d "$DATA_HOME/solrdata/data" ]; then
        log_info "solrdata/data subdirectory:"
        ls -ld "$DATA_HOME/solrdata/data"
    fi

    # Warn if directory is empty (might indicate mount isn't working)
    if [ -z "$(ls -A $DATA_HOME/solrdata 2>/dev/null)" ]; then
        log_warning "solrdata directory is EMPTY - volume mount may not be working correctly"
        log_warning "Data may be written to container's internal filesystem instead of host"
    else
        log_success "solrdata directory has contents (volume mount appears to be working)"
        log_info "Contents:"
        ls -la "$DATA_HOME/solrdata"
    fi
else
    log_error "solrdata directory does not exist at $DATA_HOME/solrdata"
    cleanup_and_exit 1
fi

# ============================================================================
# Wait for Solr to Start
# ============================================================================

log_info "==============================================="
log_info "Waiting for Solr to Start"
log_info "==============================================="

# Wait for Solr to be fully ready with better diagnostics
log_info "Waiting for Solr to be ready..."
SOLR_READY=false

for i in {1..30}; do
    if curl -s "http://localhost:8983/solr/admin/cores?action=STATUS" > /dev/null 2>&1; then
        log_success "Solr is ready"
        SOLR_READY=true
        break
    fi

    # Show progress every 5 seconds
    if [ $((i % 5)) -eq 0 ]; then
        log_info "Still waiting... (attempt $i/30)"

        # Check if container is still running
        if ! docker ps | grep -q registry-legacy; then
            log_error "Registry container stopped running!"
            log_info "Last 50 lines of container logs:"
            docker logs registry-legacy 2>&1 | tail -50
            cleanup_and_exit 1
        fi
    fi

    sleep 1
done

if [ "$SOLR_READY" = false ]; then
    log_error "Solr failed to start within 30 seconds"

    log_info "=== Container Status ==="
    docker ps -a | grep registry-legacy

    log_info "=== Container Logs (last 100 lines) ==="
    docker logs registry-legacy 2>&1 | tail -100

    log_info "=== Attempting to check Solr logs inside container ==="
    docker exec registry-legacy cat /var/solr/logs/solr.log 2>&1 | tail -50 || \
        log_warning "Could not access Solr logs inside container"

    cleanup_and_exit 1
fi

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
