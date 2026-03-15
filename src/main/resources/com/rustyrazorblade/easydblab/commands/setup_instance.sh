#!/usr/bin/env bash

###### CONFIGURATION ######
## ANY VARIABLE NEEDED IN THIS SCRIPT
## SHOULD BE SET IN THIS BLOCK

export READAHEAD=8

# bcache configuration — injected by easy-db-lab at init time
export BCACHE_ENABLED=__BCACHE_ENABLED__
export BCACHE_MODE=__BCACHE_MODE__

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

###### NVMe DEVICE DISCOVERY ######
# Use nvme id-ctrl to identify each NVMe device by its model string:
#   "Amazon EC2 NVMe Instance Storage" -> local instance store (cache device for bcache)
#   "Amazon Elastic Block Store"       -> EBS volume (backing device for bcache)
CACHE_DEVICES=()
EBS_DEVICES=()

for DEV in /dev/nvme*n1; do
  [[ -b "$DEV" ]] || continue
  MODEL=$(sudo nvme id-ctrl "$DEV" 2>/dev/null | grep "^mn" | cut -d: -f2 | xargs)
  case "$MODEL" in
    "Amazon EC2 NVMe Instance Storage")
      CACHE_DEVICES+=("$DEV")
      ;;
    "Amazon Elastic Block Store")
      EBS_DEVICES+=("$DEV")
      ;;
    *)
      echo "Unknown NVMe device model on $DEV: '$MODEL', skipping"
      ;;
  esac
done

echo "Discovered ${#CACHE_DEVICES[@]} instance store device(s): ${CACHE_DEVICES[*]:-none}"
echo "Discovered ${#EBS_DEVICES[@]} EBS device(s): ${EBS_DEVICES[*]:-none}"
###### END NVMe DEVICE DISCOVERY ######

if [[ "${BCACHE_ENABLED}" == "true" ]]; then
  ###### BCACHE SETUP ######
  # Configure local NVMe instance store(s) as a bcache cache set in front of an EBS backing device.
  # Multiple cache devices are combined into a single bcache cache set.
  # The resulting /dev/bcache0 virtual device is formatted and mounted at /mnt/db1.

  if [[ ${#CACHE_DEVICES[@]} -eq 0 ]]; then
    echo "ERROR: No local NVMe instance store devices found. bcache requires instance storage." >&2
    exit 1
  fi

  if [[ ${#EBS_DEVICES[@]} -eq 0 ]]; then
    echo "ERROR: No EBS NVMe devices found. bcache requires an EBS backing device." >&2
    exit 1
  fi

  BACKING_DEVICE="${EBS_DEVICES[0]}"
  if [[ ${#EBS_DEVICES[@]} -gt 1 ]]; then
    echo "Multiple EBS devices found (${EBS_DEVICES[*]}). Using ${BACKING_DEVICE} as bcache backing device."
  fi

  echo "Setting up bcache: cache=${CACHE_DEVICES[*]}, backing=${BACKING_DEVICE}, mode=${BCACHE_MODE}"

  # Load the bcache kernel module
  sudo modprobe bcache

  # Wipe any existing bcache superblocks for idempotency
  for DEV in "${CACHE_DEVICES[@]}" "${BACKING_DEVICE}"; do
    sudo wipefs -a "$DEV" || true
  done

  # Register all cache devices in a single cache set (bcache supports multiple cache devices)
  sudo make-bcache -C "${CACHE_DEVICES[@]}"

  # Obtain the cache set UUID
  CSET_UUID=$(ls /sys/fs/bcache/)

  # Register the backing device (EBS)
  sudo make-bcache -B "${BACKING_DEVICE}"

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
  echo "bcache setup complete. Using ${DISK} (${#CACHE_DEVICES[@]} cache device(s), mode: ${BCACHE_MODE})"
  ###### END BCACHE SETUP ######

else
  ###### STANDARD DISK DETECTION ######
  # Prefer local instance store NVMe, fall back to EBS NVMe, then xvdb for non-Nitro instances.
  if [[ ${#CACHE_DEVICES[@]} -gt 0 ]]; then
    DISK="${CACHE_DEVICES[0]}"
  elif [[ ${#EBS_DEVICES[@]} -gt 0 ]]; then
    DISK="${EBS_DEVICES[0]}"
  else
    # Fallback for non-Nitro instances with legacy device names
    for VOL in xvdb xvdc; do
      if [[ -b "/dev/$VOL" ]]; then
        DISK="/dev/$VOL"
        break
      fi
    done
  fi
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
