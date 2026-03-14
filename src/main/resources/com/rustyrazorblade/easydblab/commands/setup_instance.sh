#!/usr/bin/env bash

###### CONFIGURATION ######
## ANY VARIABLE NEEDED IN THIS SCRIPT
## SHOULD BE SET IN THIS BLOCK

export READAHEAD=8

# bcache configuration — injected by easy-db-lab at init time
export BCACHE_ENABLED=__BCACHE_ENABLED__
export BCACHE_MODE=__BCACHE_MODE__
export EBS_DEVICE=__EBS_DEVICE__

DISK=""

###### END CONFIGURATION ###
###########################

###### SYSTEM SETTINGS // OS TUNINGS #####

sudo sysctl kernel.perf_event_paranoid=1
sudo sysctl kernel.kptr_restrict=0

echo 0 > /proc/sys/vm/zone_reclaim_mode

cat <<EOF | sudo tee /etc/security/limits.d/cassandra.conf
cassandra soft memlock unlimited
cassandra hard memlock unlimited
cassandra soft nofile 100000
cassandra hard nofile 100000
cassandra soft nproc 32768
cassandra hard nproc 32768
cassandra - as unlimited
EOF

cat <<EOF | sudo tee /etc/sysctl.d/60-cassandra.conf
vm.max_map_count = 1048575
EOF

sudo swapoff --all

sudo sysctl -p /etc/sysctl.d/60-cassandra.conf
########

sudo mkdir -p /mnt/db1

if [[ "${BCACHE_ENABLED}" == "true" ]]; then
  ###### BCACHE SETUP ######
  # Configure local NVMe as bcache cache device in front of the EBS backing device.
  # The resulting /dev/bcache0 virtual device is then formatted and mounted at /mnt/db1.

  echo "Setting up bcache: cache=NVMe, backing=${EBS_DEVICE}, mode=${BCACHE_MODE}"

  # Load the bcache kernel module
  sudo modprobe bcache

  # Identify the local NVMe cache device (first unpartitioned NVMe)
  CACHE_DEVICE=""
  for VOL in nvme0n1 nvme1n1; do
    export VOL
    TMP=$(lsblk -o NAME,MOUNTPOINTS -J | yq '.blockdevices[] | select(.name == env(VOL)) | has("children")')
    if [[ "${TMP}" == "false" ]]; then
      CACHE_DEVICE="/dev/$VOL"
      break
    fi
  done

  if [[ -z "${CACHE_DEVICE}" ]]; then
    echo "ERROR: No unpartitioned local NVMe device found for bcache cache. Aborting." >&2
    exit 1
  fi

  echo "bcache cache device: ${CACHE_DEVICE}"
  echo "bcache backing device: ${EBS_DEVICE}"

  # Wipe any existing bcache superblocks for idempotency
  sudo wipefs -a "${CACHE_DEVICE}" || true
  sudo wipefs -a "${EBS_DEVICE}" || true

  # Register the cache device (NVMe)
  sudo make-bcache -C "${CACHE_DEVICE}"

  # Obtain the cache set UUID
  CSET_UUID=$(ls /sys/fs/bcache/)

  # Register the backing device (EBS)
  sudo make-bcache -B "${EBS_DEVICE}"

  # Wait for bcache0 to appear
  for i in $(seq 1 30); do
    if [[ -b /dev/bcache0 ]]; then
      break
    fi
    echo "Waiting for /dev/bcache0 to appear (attempt $i)..."
    sleep 1
  done

  if [[ ! -b /dev/bcache0 ]]; then
    echo "ERROR: /dev/bcache0 did not appear after registering backing device." >&2
    exit 1
  fi

  # Attach cache set to the backing device
  echo "${CSET_UUID}" | sudo tee /sys/block/bcache0/bcache/attach

  # Set the requested cache mode
  echo "${BCACHE_MODE}" | sudo tee /sys/block/bcache0/bcache/cache_mode

  DISK=/dev/bcache0
  echo "bcache setup complete. Using ${DISK} (mode: ${BCACHE_MODE})"
  ###### END BCACHE SETUP ######

else
  ###### STANDARD DISK DETECTION ######
  for VOL in nvme0n1 nvme1n1 xvdb; do
    export VOL
    echo "Checking $VOL"
    TMP=$(lsblk -o NAME,MOUNTPOINTS -J | yq '.blockdevices[] | select(.name == env(VOL)) | has("children")')
    echo $TMP

    if [[ "${TMP}" == "false" ]]; then
      DISK="/dev/$VOL"
      break
    fi
  done
  ###### END STANDARD DISK DETECTION ######
fi

echo "Using disk: $DISK"

if [[ -n "$DISK" ]]; then
  FS_TYPE=$(sudo blkid -o value -s TYPE $DISK )

  if [ -z "$FS_TYPE" ]; then
    echo "No file system found on $DISK. Formatting with XFS."
    sudo mkfs.xfs $DISK
  else
    echo "File system found on $DISK. Not formatting."
  fi

  sudo mount | grep $DISK

  if [ $? -eq 0 ]; then
      echo "$1 is mounted already."
  else
      echo "$1 is not mounted yet, mounting."
      sudo mount $DISK /mnt/db1
  fi

  sudo blockdev --setra $READAHEAD $DISK
fi

# Create database-specific subdirectories
sudo mkdir -p /mnt/db1/cassandra
sudo mkdir -p /mnt/db1/clickhouse
sudo mkdir -p /mnt/db1/otel

# Create symlink for backwards compatibility
sudo ln -sf /mnt/db1/cassandra /mnt/cassandra

sudo mkdir -p /mnt/db1/cassandra/artifacts
chmod 777 /mnt/db1/cassandra/artifacts

sudo mkdir -p /mnt/db1/cassandra/import
sudo mkdir -p /mnt/db1/cassandra/logs/sidecar
sudo mkdir -p /mnt/db1/cassandra/saved_caches

sudo chown -R cassandra:cassandra /mnt/db1/cassandra

# Stress directory owned by ubuntu for stress test output
sudo mkdir -p /mnt/db1/cassandra/stress
sudo chown ubuntu:ubuntu /mnt/db1/cassandra/stress

# ClickHouse runs as UID 101 inside the container
sudo mkdir -p /mnt/db1/clickhouse/keeper
sudo chown -R 101:101 /mnt/db1/clickhouse

sudo mkdir -p /mnt/db1/cassandra/tmp
sudo chmod 777 /mnt/db1/cassandra/tmp/

# enable cap_perfmon for all JVMs to allow for off-cpu profiling
sudo find /usr/lib/jvm/ -type f -name 'java' -exec setcap "cap_perfmon,cap_sys_ptrace,cap_syslog=ep" {} \;
