### Requirement: bcache write-back caching for database nodes

The system SHALL support an optional bcache write-back caching configuration for database nodes, enabled by passing `--bcache` to the `init` command. When enabled, the local NVMe instance store SHALL be configured as the bcache cache device and the EBS volume SHALL be configured as the backing device. The resulting `/dev/bcache0` virtual block device SHALL be formatted with XFS and mounted to `/mnt/db1`, replacing the direct EBS or NVMe mount.

bcache is disabled by default (`--bcache` is a boolean flag, default `false`).

#### Scenario: bcache disabled (default)

- **WHEN** the user runs `init` without `--bcache`
- **THEN** the system SHALL use the existing storage setup logic (NVMe or EBS directly, no caching layer)

#### Scenario: bcache enabled with valid prerequisites

- **WHEN** the user runs `init` with `--bcache`, an instance type that has local NVMe instance store, and `--ebs.type` set to a non-NONE value
- **THEN** the system SHALL store `bcache = true` in `InitConfig`
- **AND** the generated `setup_instance.sh` SHALL configure NVMe as the bcache cache device and EBS as the bcache backing device
- **AND** the database SHALL be stored on `/dev/bcache0` mounted at `/mnt/db1`
- **AND** the bcache cache mode SHALL be set to writeback

### Requirement: bcache cache mode

The system SHALL configure bcache in **writeback** mode. In writeback mode, writes are acknowledged after being stored in the NVMe cache, and flushed to the EBS backing device asynchronously. This maximises write performance at the cost of a small window of data loss if the instance is terminated before dirty cache lines are flushed.

This risk is acceptable for test workloads where data durability between test runs is not required.

### Requirement: bcache device detection

The system SHALL identify the cache and backing devices as follows:

- **Cache device (NVMe):** The first local NVMe device available on the instance (typically `/dev/nvme0n1`). This is the same device the non-bcache path uses as the primary data disk on instance-store instances.
- **Backing device (EBS):** The EBS volume attached at the device path specified in `EBSConfig.deviceName` (default `/dev/xvdf`). On Nitro-based instances, the AWS NVMe driver creates a udev symlink from `/dev/xvdf` to the actual NVMe device name (`/dev/nvme1n1`). The system SHALL use `/dev/xvdf` as the canonical path and rely on this symlink for resolution.

### Requirement: bcache kernel module and tooling

The system SHALL load the `bcache` kernel module (`modprobe bcache`) before any bcache operations. The `make-bcache` utility (from the `bcache-tools` package) SHALL be available on the instance at setup time. The Packer base AMI build SHALL install `bcache-tools` and ensure the `bcache` kernel module is available (via `linux-modules-extra-aws` or equivalent).

### Requirement: bcache is not persistent across reboots

The bcache configuration performed by `setup_instance.sh` is **not** persisted across instance reboots. If the instance is rebooted, bcache devices will not be re-registered automatically. This is a known limitation for test cluster use cases.

The system SHALL NOT attempt to persist or restore bcache configuration across reboots.
