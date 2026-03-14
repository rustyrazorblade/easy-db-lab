### Requirement: bcache caching for database nodes

The system SHALL support an optional bcache caching configuration for database nodes, enabled by passing `--bcache` to the `init` command. When enabled, the local NVMe instance store SHALL be configured as the bcache cache device and the EBS volume SHALL be configured as the backing device. The resulting `/dev/bcache0` virtual block device SHALL be formatted with XFS and mounted to `/mnt/db1`, replacing the direct EBS or NVMe mount.

bcache is disabled by default (`--bcache` is a boolean flag, default `false`).

#### Scenario: bcache disabled (default)

- **WHEN** the user runs `init` without `--bcache`
- **THEN** the system SHALL use the existing storage setup logic (NVMe or EBS directly, no caching layer)

#### Scenario: bcache enabled with valid prerequisites (default mode)

- **WHEN** the user runs `init` with `--bcache` and no `--bcache.mode` flag, an instance type that has local NVMe instance store, and `--ebs.type` set to a non-NONE value
- **THEN** the system SHALL store `bcache = true` and `bcacheMode = "writethrough"` in `InitConfig`
- **AND** the generated `setup_instance.sh` SHALL configure NVMe as the bcache cache device and EBS as the bcache backing device
- **AND** the database SHALL be stored on `/dev/bcache0` mounted at `/mnt/db1`
- **AND** the bcache cache mode SHALL be set to `writethrough`

#### Scenario: bcache enabled in writeback mode

- **WHEN** the user runs `init` with `--bcache --bcache.mode=writeback`, an instance type that has local NVMe instance store, and `--ebs.type` set to a non-NONE value
- **THEN** the system SHALL store `bcache = true` and `bcacheMode = "writeback"` in `InitConfig`
- **AND** the generated `setup_instance.sh` SHALL configure the bcache cache mode as `writeback`

### Requirement: bcache cache mode

The system SHALL support two bcache cache modes, selectable via `--bcache.mode`:

- **`writethrough`** (default): Writes are committed to both the NVMe cache and the EBS backing device before being acknowledged. This is the safe default — there is no risk of data loss on instance termination. Write performance is bounded by EBS throughput.
- **`writeback`**: Writes are acknowledged after being stored in the NVMe cache, and flushed to the EBS backing device asynchronously. This maximises write performance at the cost of a small window of data loss if the instance is terminated before dirty cache lines are flushed.

`writethrough` is the default because it is the safer option. Users who need maximum write performance and accept the durability trade-off should pass `--bcache.mode=writeback`.

The system SHALL reject any value for `--bcache.mode` other than `writethrough` or `writeback` with a clear error message at init time.

#### Scenario: invalid cache mode

- **WHEN** the user runs `init` with `--bcache --bcache.mode=<invalid>`
- **THEN** the system SHALL fail with a clear error message listing the accepted values (`writethrough`, `writeback`)

### Requirement: bcache device detection

The system SHALL identify the cache and backing devices as follows:

- **Cache device (NVMe):** The first local NVMe device available on the instance (typically `/dev/nvme0n1`). This is the same device the non-bcache path uses as the primary data disk on instance-store instances.
- **Backing device (EBS):** The EBS volume attached at the device path specified in `EBSConfig.deviceName` (default `/dev/xvdf`). On Nitro-based instances, the AWS NVMe driver creates a udev symlink from `/dev/xvdf` to the actual NVMe device name (`/dev/nvme1n1`). The system SHALL use `/dev/xvdf` as the canonical path and rely on this symlink for resolution.

### Requirement: bcache kernel module and tooling

The system SHALL load the `bcache` kernel module (`modprobe bcache`) before any bcache operations. The `make-bcache` utility (from the `bcache-tools` package) SHALL be available on the instance at setup time. The Packer base AMI build SHALL install `bcache-tools` and ensure the `bcache` kernel module is available (via `linux-modules-extra-aws` or equivalent).

### Requirement: bcache is not persistent across reboots

The bcache configuration performed by `setup_instance.sh` is **not** persisted across instance reboots. If the instance is rebooted, bcache devices will not be re-registered automatically. This is a known limitation for test cluster use cases.

The system SHALL NOT attempt to persist or restore bcache configuration across reboots.
