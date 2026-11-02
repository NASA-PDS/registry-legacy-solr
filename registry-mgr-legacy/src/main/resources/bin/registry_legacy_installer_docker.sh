#!/usr/bin/env bash

SOLR_HEAP=2048m

maxShardsPerNode=1
numShards=1
replicationFactor=1

DOCKER_IMAGE=registry-legacy
DOCKER_VOLUME="solrdata"

COLLECTIONS="registry data"
SOLR_URL="http://localhost:8983/solr"
SOLR_ZK="localhost:9983"
SOLR_BACKUP_MOUNT="/backups"

PROMPT=true
SKIP_BACKUP=false
COMMAND=""
BACKUP_LABEL=""

SCRIPT_DIR=$(cd "$( dirname $0 )" && pwd)
PARENT_DIR=$(cd ${SCRIPT_DIR}/.. && pwd)
LOG=${HOME}/registry_installer_docker-log-$(date '+%Y%m%d_%H%M%S').txt

VERSION_FILE=${PARENT_DIR}/VERSION.txt
VERSION="latest"


function print_usage() {
  echo ""
  echo "Usage: $0 [OPTIONS] COMMAND"
  echo ""
  echo "Options:"
  echo "  -n   No prompt"
  echo "  -s   Skip the pre-upgrade backup (upgrade only)"
  echo ""
  echo "Commands:"
  echo "  install          Install registry in docker"
  echo "  uninstall        Uninstall all registry components from docker"
  echo "  backup [LABEL]   Back up the registry and data collections to BACKUP_HOME"
  echo "  upgrade          Back up, then replace the container with a newly built image,"
  echo "                   keeping the existing indexes"
  echo "  restore [LABEL]  Recreate the container and restore the collections from a"
  echo "                   backup (the latest backup if LABEL is omitted)"
  echo ""
  echo "Environment:"
  echo "  DATA_HOME    Directory holding the Solr volume (required)"
  echo "  BACKUP_HOME  Directory holding collection backups (default: \$DATA_HOME/solr-backups)"
  echo ""
}

# Check number of command line parameters
if [[ $# -eq 0 ]]; then
  print_usage
  exit
fi

# Parse command line parameters
while [ $# -gt 0 ]; do
  if [[ "$1" == "-n" ]]; then
    PROMPT=false
  elif [[ "$1" == "-s" ]]; then
    SKIP_BACKUP=true
  elif [[ ("$1" == "install" || "$1" == "uninstall" || "$1" == "backup" || "$1" == "upgrade" || "$1" == "restore") ]]; then
    COMMAND="$1"
  elif [[ -n "$COMMAND" && -z "$BACKUP_LABEL" && "$1" =~ ^[A-Za-z0-9_.-]+$ ]]; then
    BACKUP_LABEL="$1"
  else
    echo -e "\nUnrecognized argument: $1\n"
    exit 1
  fi
  shift
done

# Check DATA_HOME is set
if [ -z "$DATA_HOME" ]; then
  echo "ERROR: DATA_HOME environment variable must be set for maintaining Solr index."
  exit 1
fi

# Backups live outside the Solr volume so that they survive its deletion
BACKUP_HOME=${BACKUP_HOME:-${DATA_HOME}/solr-backups}

# Version
if [ -f "$VERSION_FILE" ]; then
  VERSION=$(head -n 1 "$VERSION_FILE")
  # An unfiltered build leaves the Maven placeholder in VERSION.txt
  if [[ "$VERSION" == '${project.version}' ]]; then
    echo "WARNING: No version set in $VERSION_FILE. Will use default version 'latest'"
    VERSION="latest"
  fi
else
  echo "WARNING: $VERSION_FILE doesn't exist. Will use default version 'latest'"
fi

print_status() {
  local exit_code=$1

  if [[ $exit_code -eq 0 ]]; then
    echo "SUCCESS"
  else
    echo "FAILED"
    echo "See $LOG"
    echo
    exit 1
  fi
}

print_solr_status() {
    status=$(echo $1 | tr -d '\n' | awk -F"status" '{print $2}' | awk -F, '{print $1}' | awk -F: '{print $2}')
    print_status $((status))
}

build_docker_image() {
    echo -ne "Building Registry Docker Image.                               " | tee -a $LOG
    cd ${PARENT_DIR}/build
    docker build -t $DOCKER_IMAGE:$VERSION -f Dockerfile ../ | tee -a $LOG 2>&1
    print_status $?
}

set_solr_ownership() {
    local dir=$1
    local chown_opts=$2

    # Ensure Solr user (8983) can write to the directory
    # In CI environments, this directory may be created with wrong ownership
    if command -v chown > /dev/null 2>&1; then
        # Detect if we're in CI and sudo is available
        CHOWN_CMD="chown"
        if [[ -n "$CI" || -n "$GITHUB_ACTIONS" ]] && command -v sudo > /dev/null 2>&1; then
            CHOWN_CMD="sudo chown"
            echo "  Detected CI environment, using sudo for ownership change" | tee -a $LOG
        fi

        # Attempt to change ownership to Solr user (8983)
        if $CHOWN_CMD $chown_opts 8983:8983 $dir >>$LOG 2>&1; then
            echo "  Successfully set ownership to Solr user (8983:8983)" | tee -a $LOG
        else
            echo "WARNING: Could not chown $dir to 8983:8983" | tee -a $LOG
            echo "         You may need to run with sudo or adjust permissions manually" | tee -a $LOG
            echo "         Current permissions:" | tee -a $LOG
            ls -la $dir | tee -a $LOG
        fi
    fi
}

create_docker_volumes() {
    echo "Creating docker volumes and associated data directory        " | tee -a $LOG
    mkdir -p $DATA_HOME/$DOCKER_VOLUME/data
    set_solr_ownership $DATA_HOME/$DOCKER_VOLUME -R

    docker volume create $DOCKER_VOLUME >>$LOG 2>&1
}

create_backup_dir() {
    echo "Creating backup directory $BACKUP_HOME" | tee -a $LOG
    mkdir -p $BACKUP_HOME
    set_solr_ownership $BACKUP_HOME
}

remove_registry_container() {
    containerId=$(docker ps -a | grep "registry" | awk '{print $1}')
    if [ ! -z "$containerId" ]; then
        docker stop $containerId >> $LOG 2>&1
        docker rm $containerId >> $LOG 2>&1
    fi
}

wait_for_solr() {
    attempt_counter=0
    max_attempts=12

    echo "Waiting for Solr server to start." | tee -a $LOG

    until $(curl --output /dev/null --max-time 5 --silent --head --fail http://localhost:8983/solr); do
        if [ ${attempt_counter} -eq ${max_attempts} ];then
            echo "Could not start Solr."
            exit 1
        fi
        attempt_counter=$(($attempt_counter+1))
        sleep 5
    done
}

start_registry_container() {
    echo -ne "Starting Registry Docker Container                            " | tee -a $LOG
    docker run --name ${DOCKER_IMAGE} \
      -u solr \
      -d -p 8983:8983 \
      -v ${DATA_HOME}/${DOCKER_VOLUME}:/var/solr/ \
      -v ${BACKUP_HOME}:${SOLR_BACKUP_MOUNT} \
      -e SOLR_HEAP=$SOLR_HEAP \
      -e SOLR_OPTS="-Dsolr.allowPaths=${SOLR_BACKUP_MOUNT}" \
      --restart unless-stopped \
      $DOCKER_IMAGE:$VERSION | tee -a $LOG 2>&1

    print_status $?
}

create_solr_collections() {
    # Create the Registry collections
    echo -ne "Creating Registry collection (registry)                       " | tee -a $LOG
    docker exec --user=solr ${DOCKER_IMAGE} solr create -c registry -d registry -shards ${numShards} -replicationFactor ${replicationFactor} >>$LOG 2>&1
    print_status $?

    # Create the Search collection 
    echo -ne "Creating Search collection (data)                             " | tee -a $LOG
    docker exec --user=solr ${DOCKER_IMAGE} solr create -c data -d data -shards ${numShards} -replicationFactor ${replicationFactor} >>$LOG 2>&1
    print_status $?
}

confirm_uninstall() {
    if [ "$PROMPT" = true ]; then
        while true; do
            echo ""
            read -p "Are you sure you want to uninstall the Registry and Search and all associated indices? (y/n) " yn
            case $yn in
                [Yy]* ) break;;
                [Nn]* ) exit 0;;
                * ) echo "Please answer y[es] or n[o].";;
            esac
        done
    fi
}

remove_solr_collections() {
    # Remove 'registry' collection
    echo "Removing the Registry collection.                                 " | tee -a $LOG
    docker exec --user=solr ${DOCKER_IMAGE} solr delete -c registry >>$LOG 2>&1

    # Remove 'data' collection
    echo "Removing the Search collection.                                   " | tee -a $LOG
    docker exec --user=solr ${DOCKER_IMAGE} solr delete -c data >>$LOG 2>&1
}

stop_solr() {
    echo "Stopping the SOLR instance.                                       " | tee -a $LOG
    docker exec ${DOCKER_IMAGE} solr stop >>$LOG 2>&1
}

remove_registry_image() {
    echo "Removing Registry Docker Images.                                  " | tee -a $LOG
    docker rmi -f "$DOCKER_IMAGE:$VERSION" >>$LOG 2>&1
}

remove_docker_volumes() {
	echo "Removing '"$DOCKER_VOLUME"' volume                   " | tee -a $LOG
	docker volume rm $DOCKER_VOLUME  >>$LOG 2>&1
}

ensure_registry_running() {
    local running
    running=$(docker inspect -f '{{.State.Running}}' ${DOCKER_IMAGE} 2>/dev/null)
    if [[ -z "$running" ]]; then
        echo "ERROR: Docker container '${DOCKER_IMAGE}' does not exist. Use 'install' or 'restore'." >&2
        exit 1
    fi
    if [[ "$running" != "true" ]]; then
        echo "Starting existing container '${DOCKER_IMAGE}'" | tee -a $LOG
        docker start ${DOCKER_IMAGE} >>$LOG 2>&1
        print_status $?
        wait_for_solr
    fi
}

check_backup_mount() {
    if ! docker exec --user=solr ${DOCKER_IMAGE} test -w ${SOLR_BACKUP_MOUNT} >>$LOG 2>&1; then
        echo "ERROR: ${SOLR_BACKUP_MOUNT} is not writable by Solr in container '${DOCKER_IMAGE}'." >&2
        echo "       The container was created without the backup mount, or $BACKUP_HOME has the wrong ownership." >&2
        echo "       Take a cold copy of $DATA_HOME/$DOCKER_VOLUME and run '-s upgrade' to continue without a backup." >&2
        exit 1
    fi
}

wait_for_collections() {
    for collection in ${COLLECTIONS}; do
        attempt_counter=0
        max_attempts=24

        echo "Waiting for collection (${collection}) to become available." | tee -a $LOG
        until curl --output /dev/null --max-time 10 --silent --fail "${SOLR_URL}/${collection}/select?q=*:*&rows=0"; do
            if [[ ${attempt_counter} -eq ${max_attempts} ]]; then
                echo "Collection (${collection}) did not become available." >&2
                exit 1
            fi
            attempt_counter=$(($attempt_counter+1))
            sleep 5
        done
    done
}

# Wait for an async Collections API request (BACKUP, RESTORE) to finish.
wait_for_async() {
    local request_id=$1
    local attempt_counter=0
    local max_attempts=1440
    local response state

    while [[ ${attempt_counter} -lt ${max_attempts} ]]; do
        response=$(curl --silent --max-time 30 "${SOLR_URL}/admin/collections?action=REQUESTSTATUS&requestid=${request_id}&wt=json" | tr -d ' \n')
        state=$(echo "$response" | sed -n 's/.*"state":"\([a-z]*\)".*/\1/p')
        if [[ "$state" == "completed" ]]; then
            curl --silent --max-time 30 "${SOLR_URL}/admin/collections?action=DELETESTATUS&requestid=${request_id}" >>$LOG 2>&1
            return 0
        elif [[ "$state" == "failed" ]]; then
            echo "$response" >>$LOG
            return 1
        fi
        attempt_counter=$(($attempt_counter+1))
        sleep 5
    done
    return 1
}

backup_solr_collections() {
    local label=$1
    local name

    for collection in ${COLLECTIONS}; do
        name=${collection}-${label}
        echo -ne "Backing up collection (${collection}) to ${BACKUP_HOME}/${name}   " | tee -a $LOG
        curl --silent --fail --max-time 60 "${SOLR_URL}/admin/collections?action=BACKUP&name=${name}&collection=${collection}&location=${SOLR_BACKUP_MOUNT}&async=backup-${name}" >>$LOG 2>&1 \
          && wait_for_async backup-${name}
        print_status $?
    done
}

restore_solr_collections() {
    local label=$1
    local name

    for collection in ${COLLECTIONS}; do
        name=${collection}-${label}
        echo -ne "Restoring collection (${collection}) from ${BACKUP_HOME}/${name}   " | tee -a $LOG
        curl --silent --fail --max-time 60 "${SOLR_URL}/admin/collections?action=RESTORE&name=${name}&collection=${collection}&collection.configName=${collection}&location=${SOLR_BACKUP_MOUNT}&async=restore-${name}" >>$LOG 2>&1 \
          && wait_for_async restore-${name}
        print_status $?
    done
}

# Push the config shipped in the current image to ZooKeeper. Configs uploaded this way are trusted,
# which the <lib> directives in solrconfig.xml require (a config restored from a backup is not).
upload_solr_config() {
    for collection in ${COLLECTIONS}; do
        echo -ne "Uploading config for collection (${collection})              " | tee -a $LOG
        docker exec --user=solr ${DOCKER_IMAGE} solr zk upconfig -z ${SOLR_ZK} -n ${collection} -d /opt/solr/server/solr/configsets/${collection}/conf >>$LOG 2>&1
        print_status $?
    done
}

reload_solr_collections() {
    for collection in ${COLLECTIONS}; do
        echo -ne "Reloading collection (${collection})                         " | tee -a $LOG
        curl --silent --fail --max-time 300 "${SOLR_URL}/admin/collections?action=RELOAD&name=${collection}" >>$LOG 2>&1
        print_status $?
    done
}

latest_backup_label() {
    ls -1 ${BACKUP_HOME} 2>/dev/null | sed -n 's/^data-//p' | sort | tail -n 1
}

check_backup_exists() {
    local label=$1

    for collection in ${COLLECTIONS}; do
        if [[ ! -d "${BACKUP_HOME}/${collection}-${label}" ]]; then
            echo "ERROR: Backup ${BACKUP_HOME}/${collection}-${label} not found." >&2
            exit 1
        fi
    done
}

ensure_collections_absent() {
    local existing
    existing=$(curl --silent --max-time 30 "${SOLR_URL}/admin/collections?action=LIST&wt=json" | tr -d ' \n')

    for collection in ${COLLECTIONS}; do
        if [[ "$existing" == *"\"${collection}\""* ]]; then
            echo "ERROR: Collection (${collection}) already exists. Run 'uninstall' to remove the current indexes before restoring." >&2
            exit 1
        fi
    done
}

confirm_restore() {
    local label=$1

    if [[ "$PROMPT" == true ]]; then
        while true; do
            echo ""
            read -p "Restore backup '$label' into a new Registry container, replacing any existing one? (y/n) " yn
            case $yn in
                [Yy]* ) break;;
                [Nn]* ) exit 0;;
                * ) echo "Please answer y[es] or n[o].";;
            esac
        done
    fi
}


# Execute commands
if [[ $COMMAND == "install" ]]; then
  echo "Installing..."
  build_docker_image
  create_docker_volumes
  create_backup_dir
  start_registry_container
  wait_for_solr
  create_solr_collections
  # Print solr status
  docker exec ${DOCKER_IMAGE} solr status | tee -a $LOG 2>&1

elif [[ $COMMAND == "uninstall" ]]; then
  echo "Uninstalling..."
  confirm_uninstall
  remove_solr_collections
  stop_solr
  remove_registry_container
  remove_registry_image
  remove_docker_volumes

elif [[ $COMMAND == "backup" ]]; then
  BACKUP_LABEL=${BACKUP_LABEL:-$(date '+%Y%m%d_%H%M%S')}
  echo "Backing up (label: ${BACKUP_LABEL})..."
  ensure_registry_running
  check_backup_mount
  backup_solr_collections $BACKUP_LABEL

elif [[ $COMMAND == "upgrade" ]]; then
  BACKUP_LABEL=${BACKUP_LABEL:-$(date '+%Y%m%d_%H%M%S')}
  echo "Upgrading..."
  ensure_registry_running
  if [[ "$SKIP_BACKUP" == false ]]; then
    check_backup_mount
    backup_solr_collections $BACKUP_LABEL
  fi
  build_docker_image
  stop_solr
  remove_registry_container
  create_backup_dir
  start_registry_container
  wait_for_solr
  wait_for_collections
  upload_solr_config
  reload_solr_collections
  docker exec ${DOCKER_IMAGE} solr status | tee -a $LOG 2>&1

elif [[ $COMMAND == "restore" ]]; then
  BACKUP_LABEL=${BACKUP_LABEL:-$(latest_backup_label)}
  if [[ -z "$BACKUP_LABEL" ]]; then
    echo "ERROR: No backups found in $BACKUP_HOME" >&2
    exit 1
  fi
  check_backup_exists $BACKUP_LABEL
  echo "Restoring (label: ${BACKUP_LABEL})..."
  confirm_restore $BACKUP_LABEL
  build_docker_image
  remove_registry_container
  create_docker_volumes
  create_backup_dir
  start_registry_container
  wait_for_solr
  ensure_collections_absent
  upload_solr_config
  restore_solr_collections $BACKUP_LABEL
  docker exec ${DOCKER_IMAGE} solr status | tee -a $LOG 2>&1
fi
