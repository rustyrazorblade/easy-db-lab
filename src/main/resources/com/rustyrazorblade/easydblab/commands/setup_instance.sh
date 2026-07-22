#!/usr/bin/env bash

###### CONFIGURATION ######
## ANY VARIABLE NEEDED IN THIS SCRIPT
## SHOULD BE SET IN THIS BLOCK

export READAHEAD=8

DISK=""

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

echo "Using disk: $DISK"

## END CONFIGURATION ###
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

  # Persist the mount so /mnt/db1 is restored automatically on every boot.
  # Without this the NVMe is mounted only at provision time; after any reboot
  # the mount is gone and k3s (whose data dir is a symlink onto /mnt/db1)
  # crash-loops with "extracting data: no such file or directory".
  #
  # Instance-store NVMe device names (nvme0n1/nvme1n1/xvdb) can change across
  # reboots, so key the fstab entry on the stable XFS filesystem UUID rather
  # than the device path. 'nofail' keeps the node bootable if the instance
  # store was wiped (a stop/terminate erases instance-store, unlike a reboot)
  # and the filesystem no longer exists; 'x-systemd.device-timeout' avoids a
  # long boot hang in that case.
  FS_UUID=$(sudo blkid -o value -s UUID "$DISK")
  if [[ -n "$FS_UUID" ]]; then
    FSTAB_ENTRY="UUID=$FS_UUID /mnt/db1 xfs defaults,nofail,x-systemd.device-timeout=10s 0 2"
    # Remove any previous /mnt/db1 entry (e.g. a stale device path) before adding the current one.
    sudo sed -i '\#[[:space:]]/mnt/db1[[:space:]]#d' /etc/fstab
    echo "$FSTAB_ENTRY" | sudo tee -a /etc/fstab
    # Pick up the fstab-generated mnt-db1.mount unit so services can order against it.
    sudo systemctl daemon-reload
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
