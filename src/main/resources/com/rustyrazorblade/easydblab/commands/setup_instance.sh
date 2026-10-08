#!/usr/bin/env bash

###### CONFIGURATION ######
## ANY VARIABLE NEEDED IN THIS SCRIPT
## SHOULD BE SET IN THIS BLOCK

export READAHEAD=8

# Every node writes its data here: databases and kit PVs, the observability backends, the K3s
# data directory and pod logs. It must be the data disk, never the 20 GB root volume.
DATA_MOUNT=/mnt/db1

## END CONFIGURATION ###
###########################

# Prints why setup failed, prefixed ERROR: (SetupInstance reports that line), and exits non-zero.
fail() {
  echo "ERROR: $*" >&2
  exit 1
}

# The disk that holds the root file system (e.g. nvme0n1): the disk mounted at / or with a
# partition mounted at /. Read from lsblk rather than from the root device's name, which can be
# /dev/root.
root_disk() {
  lsblk -J -o NAME,TYPE,MOUNTPOINTS |
    yq '.blockdevices[]
        | select(((.mountpoints // []) + ([.children[]?.mountpoints[]?] // [])) | any_c(. == "/"))
        | .name' |
    head -n 1
}

# True when the device [$1] (e.g. /dev/nvme0n1p1) is the root disk [$2] or one of its partitions.
on_root_disk() {
  local name
  name=$(basename "$1")
  [[ "$name" == "$2" || "$name" =~ ^$2p?[0-9]+$ ]]
}

# The first unused data disk: a whole disk (not a partition, loop or rom device) that is not the
# root disk, has no partitions and nothing mounted. Instance store and EBS data volumes both
# qualify, whatever their device name (nvme1n1, nvme2n1, xvdb, ...).
find_data_disk() {
  local root
  root=$(root_disk)
  lsblk -J -o NAME,TYPE,MOUNTPOINTS |
    ROOT_DISK="$root" yq '.blockdevices[]
        | select(.type == "disk")
        | select(.name != env(ROOT_DISK))
        | select(has("children") | not)
        | select(((.mountpoints // []) | map(select(. != null)) | length) == 0)
        | .name' |
    head -n 1
}

# Finds the data disk, formats it if it has no file system, mounts it at $DATA_MOUNT, persists the
# mount in /etc/fstab, and checks that $DATA_MOUNT is a mount point of a non-root device. Fails,
# naming the reason, when there is no data disk or the mount does not take. Never falls back to a
# plain directory on the root volume.
mount_data_disk() {
  local disk name fs_type fs_uuid mounted_source

  if mountpoint -q "$DATA_MOUNT"; then
    # A re-run of setup on a node whose data disk is already mounted.
    disk=$(findmnt -n -o SOURCE "$DATA_MOUNT")
    echo "$DATA_MOUNT is already mounted from $disk"
  else
    name=$(find_data_disk)
    if [[ -z "$name" || "$name" == "null" ]]; then
      fail "no data disk found: no unused non-root block device to mount at $DATA_MOUNT (lsblk: $(lsblk -n -o NAME,TYPE,MOUNTPOINTS | tr '\n' ' '))"
    fi
    disk="/dev/$name"
    echo "Using disk: $disk"

    fs_type=$(sudo blkid -o value -s TYPE "$disk")
    if [[ -z "$fs_type" ]]; then
      echo "No file system found on $disk. Formatting with XFS."
      sudo mkfs.xfs "$disk" || fail "formatting $disk with XFS failed"
    else
      echo "File system found on $disk. Not formatting."
    fi

    sudo mkdir -p "$DATA_MOUNT"
    sudo mount "$disk" "$DATA_MOUNT" || fail "mounting $disk at $DATA_MOUNT failed"

    # Persist the mount so it is restored on every boot; without it k3s (whose data dir is a
    # symlink onto /mnt/db1) crash-loops after a reboot with "extracting data: no such file or
    # directory". Device names can change across reboots, so key the entry on the file system
    # UUID. 'nofail' keeps the node bootable if a stop/terminate wiped the instance store, and
    # 'x-systemd.device-timeout' avoids a long boot hang in that case.
    fs_uuid=$(sudo blkid -o value -s UUID "$disk")
    if [[ -n "$fs_uuid" ]]; then
      # Remove any previous entry (e.g. a stale device path) before adding the current one.
      sudo sed -i "\#[[:space:]]$DATA_MOUNT[[:space:]]#d" /etc/fstab
      echo "UUID=$fs_uuid $DATA_MOUNT xfs defaults,nofail,x-systemd.device-timeout=10s 0 2" | sudo tee -a /etc/fstab
      # Pick up the fstab-generated mnt-db1.mount unit so services can order against it.
      sudo systemctl daemon-reload
    fi
  fi

  mountpoint -q "$DATA_MOUNT" || fail "$DATA_MOUNT is not a mount point after mounting $disk"
  mounted_source=$(findmnt -n -o SOURCE "$DATA_MOUNT")
  if on_root_disk "$mounted_source" "$(root_disk)"; then
    fail "$DATA_MOUNT is mounted from $mounted_source, which is on the root volume"
  fi
  echo "$DATA_MOUNT is mounted from $mounted_source"

  sudo blockdev --setra "$READAHEAD" "$mounted_source"
}

main() {
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

mount_data_disk

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

# JFR chunks from runtime profiling. Deliberately NOT artifacts/, which is 777 and holds
# operator-dropped heap dumps and jstacks that the profiling auto-pruner must never delete.
sudo mkdir -p /mnt/db1/cassandra/profiles

sudo chown -R cassandra:cassandra /mnt/db1/cassandra

# Stress directory owned by ubuntu for stress test output
sudo mkdir -p /mnt/db1/cassandra/stress
sudo chown ubuntu:ubuntu /mnt/db1/cassandra/stress

# ClickHouse runs as UID 101 inside the container
sudo mkdir -p /mnt/db1/clickhouse/keeper
sudo chown -R 101:101 /mnt/db1/clickhouse

sudo mkdir -p /mnt/db1/cassandra/tmp
sudo chmod 777 /mnt/db1/cassandra/tmp/

# Start the profiling reconciler's timer. Only the Cassandra AMI carries the unit, so this is a
# no-op on control and stress nodes. Without it, profiling would only ever run for as long as a
# CLI command happened to be running.
if [ -f /etc/systemd/system/edl-profiling-reconcile.timer ]; then
  sudo systemctl daemon-reload
  sudo systemctl enable --now edl-profiling-reconcile.timer
fi

# enable cap_perfmon for all JVMs to allow for off-cpu profiling
sudo find /usr/lib/jvm/ -type f -name 'java' -exec setcap "cap_perfmon,cap_sys_ptrace,cap_syslog=ep" {} \;
}

# Sourcing the script (its unit test does) defines the functions without running setup.
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
