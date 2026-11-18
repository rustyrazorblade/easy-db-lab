#!/usr/bin/env bash

###### CONFIGURATION ######
## ANY VARIABLE NEEDED IN THIS SCRIPT
## SHOULD BE SET IN THIS BLOCK

# The packaged tuning files (node-os-tuning), uploaded beside this script by node setup.
TUNING_DIR="$(dirname "${BASH_SOURCE[0]}")/node-tuning"

# The units that get the database process limits. A drop-in for a unit that is not installed yet
# (k3s and k3s-agent come from the K3s installer later in `up`) is read when the unit appears.
# Stress runs as a K8s Job, so it gets them from k3s-agent.
LIMITED_UNITS="cassandra k3s k3s-agent"

# The instance type node setup read from the cluster state (EDL_INSTANCE_TYPE), kept where
# edl-node-tuning reads it at boot to name the instance in a skip line.
TUNING_DEFAULTS=/etc/default/edl-node-tuning

# The file system table tune_node turns swap off in.
FSTAB=/etc/fstab

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
  local name partition
  name=$(basename "$1")
  # nvme0n1 has partitions nvme0n1p1; xvda has xvda1. nvme0n10 is another disk, not a partition.
  if [[ "$2" =~ [0-9]$ ]]; then partition='p[0-9]+'; else partition='[0-9]+'; fi
  [[ "$name" == "$2" || "$name" =~ ^$2$partition$ ]]
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
    # A node set up before noatime, or mounted by hand, gets it now and on every boot.
    fs_uuid=$(sudo blkid -o value -s UUID "$disk")
    fs_type=$(sudo blkid -o value -s TYPE "$disk")
    if [[ -n "$fs_uuid" ]]; then
      persist_mount "$fs_uuid" "$fs_type"
    fi
    sudo mount -o remount,noatime "$DATA_MOUNT" || fail "remounting $DATA_MOUNT with noatime failed"
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
      fs_type=xfs
    else
      echo "File system found on $disk ($fs_type). Not formatting."
    fi

    sudo mkdir -p "$DATA_MOUNT"
    sudo mount -o noatime "$disk" "$DATA_MOUNT" || fail "mounting $disk at $DATA_MOUNT failed"

    fs_uuid=$(sudo blkid -o value -s UUID "$disk")
    if [[ -n "$fs_uuid" ]]; then
      persist_mount "$fs_uuid" "$fs_type"
    fi
  fi

  mountpoint -q "$DATA_MOUNT" || fail "$DATA_MOUNT is not a mount point after mounting $disk"
  mounted_source=$(findmnt -n -o SOURCE "$DATA_MOUNT")
  if on_root_disk "$mounted_source" "$(root_disk)"; then
    fail "$DATA_MOUNT is mounted from $mounted_source, which is on the root volume"
  fi
  echo "$DATA_MOUNT is mounted from $mounted_source"
}

# Persists the data disk mount (file system [$1] UUID, type [$2]) in $FSTAB, replacing any
# earlier entry for $DATA_MOUNT. Without it k3s (whose data dir is a symlink onto /mnt/db1)
# crash-loops after a reboot with "extracting data: no such file or directory". Device names can
# change across reboots, so the entry is keyed on the UUID. 'noatime' saves a metadata write per
# read, 'nofail' keeps the node bootable if a stop/terminate wiped the instance store, and
# 'x-systemd.device-timeout' avoids a long boot hang in that case.
persist_mount() {
  sudo sed -i "\#[[:space:]]${DATA_MOUNT}[[:space:]]#d" "$FSTAB"
  echo "UUID=$1 $DATA_MOUNT $2 defaults,noatime,nofail,x-systemd.device-timeout=10s 0 2" | sudo tee -a "$FSTAB"
  # Pick up the fstab-generated mnt-db1.mount unit so services can order against it.
  sudo systemctl daemon-reload
}

# Installs one packaged tuning file with [mode] at [target], creating its directory. A file this
# script names but the upload lacks (a workspace setup_instance.sh older than the CLI that
# uploaded the files) fails setup, naming it, rather than leaving the node half tuned.
install_tuning_file() {
  sudo install -m "$1" -D "$TUNING_DIR/$2" "$3" || fail "could not install the node tuning file $2 to $3"
}

# Copies a file system table from stdin to stdout with every active swap line commented out.
comment_swap_lines() {
  sed -E '/^[^#].*[[:space:]]swap[[:space:]]/s/^/#/'
}

# Installs the packaged tuning files (node-os-tuning) and applies them now; each also applies at
# every boot. Re-running replaces the files in place. Fails, naming the setting, when one did not
# take.
tune_node() {
  [[ -d "$TUNING_DIR" ]] || fail "the node tuning files are missing: no $TUNING_DIR beside setup_instance.sh"

  # The files earlier versions of setup wrote; the new ones replace them.
  sudo rm -f /etc/sysctl.d/60-cassandra.conf /etc/security/limits.d/cassandra.conf

  install_tuning_file 0644 60-easydblab.conf /etc/sysctl.d/60-easydblab.conf
  install_tuning_file 0644 limits.conf /etc/security/limits.d/50-easydblab.conf
  install_tuning_file 0644 system.conf /etc/systemd/system.conf.d/50-easydblab-limits.conf
  local unit
  for unit in $LIMITED_UNITS; do
    install_tuning_file 0644 easydblab-limits.conf "/etc/systemd/system/$unit.service.d/50-easydblab-limits.conf"
  done
  install_tuning_file 0755 edl-node-tuning /usr/local/sbin/edl-node-tuning
  install_tuning_file 0644 edl-node-tuning.service /etc/systemd/system/edl-node-tuning.service
  install_tuning_file 0644 90-easydblab.rules /etc/udev/rules.d/90-easydblab.rules

  echo "EDL_INSTANCE_TYPE=${EDL_INSTANCE_TYPE:-}" | sudo tee "$TUNING_DEFAULTS" > /dev/null

  # Only this file: sysctl --system would apply every other sysctl.d file again too.
  local rejected
  rejected=$(sudo sysctl -p /etc/sysctl.d/60-easydblab.conf 2>&1 > /dev/null) ||
    fail "sysctl did not apply /etc/sysctl.d/60-easydblab.conf: $(echo "$rejected" | tr '\n' ' ')"

  sudo swapoff --all
  # Keep swap off after a reboot too.
  local fstab
  fstab=$(comment_swap_lines < "$FSTAB") || fail "could not read $FSTAB to turn swap off"
  [[ "$fstab" == "$(cat "$FSTAB")" ]] || printf '%s\n' "$fstab" | sudo tee "$FSTAB" > /dev/null

  sudo udevadm control --reload
  sudo udevadm trigger --subsystem-match=block --action=change
  sudo udevadm settle

  # PID 1 reads DefaultLimit* only on a re-exec; it keeps every service running.
  sudo systemctl daemon-reexec
  sudo systemctl daemon-reload
  # irqbalance first: the unit's own verify reads it.
  sudo systemctl enable --now irqbalance
  sudo systemctl enable edl-node-tuning.service
  sudo systemctl restart edl-node-tuning.service || fail "edl-node-tuning.service failed: $(sudo systemctl status edl-node-tuning.service --no-pager 2>&1 | tail -5 | tr '\n' ' ')"
  # The unit's lines (skips, and its verify) went to the journal; show this run's here. Each is
  # prefixed, so only the verify below can report setup's failure.
  sudo journalctl --sync
  # One invocation, so a re-run does not repeat the lines of earlier ones.
  sudo journalctl -b \
    _SYSTEMD_INVOCATION_ID="$(sudo systemctl show -p InvocationID --value edl-node-tuning.service)" --no-pager -o cat | sed 's/^/edl-node-tuning.service: /'

  sudo /usr/local/sbin/edl-node-tuning verify || exit 1
}

main() {
tune_node

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
