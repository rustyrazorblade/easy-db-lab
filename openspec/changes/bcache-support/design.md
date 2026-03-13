## Context

The current `setup_instance.sh` detects the first unpartitioned disk from a fixed list (`nvme0n1`, `nvme1n1`, `xvdb`), formats it with XFS if needed, and mounts it to `/mnt/db1`. It has no concept of layered block devices. The script is extracted from the classpath as-is by `Init.extractResourceFile()` with no template substitution — the same static file is written regardless of how the cluster was configured.

`InitConfig` already tracks EBS parameters (`ebsType`, `ebsSize`, `ebsIops`, `ebsThroughput`, `ebsOptimized`). Storage validation is done in `DefaultInstanceSpecFactory.createInstanceSpecs()`, which calls `EC2InstanceService.hasInstanceStore()` using the AWS `DescribeInstanceTypes` API.

bcache requires:
- A **cache device**: a fast local NVMe (`/dev/nvme0n1` or similar)
- A **backing device**: a slower persistent EBS volume (`/dev/xvdf` or whatever device name the EBS is attached as)
- Both devices must be **unformatted** when bcache registers them
- The resulting virtual device (`/dev/bcache0`) is then formatted with XFS and mounted

## Goals / Non-Goals

**Goals:**
- Add `--bcache` flag to `init`, stored in `InitConfig`.
- Validate at init time: `--bcache` requires `--ebs.type != NONE` AND instance type has local instance store. Fail fast if either is absent.
- Generate `setup_instance.sh` with bcache-awareness by injecting `BCACHE_ENABLED` (true/false) via TemplateService template substitution.
- In the bcache path of `setup_instance.sh`: make NVMe the cache device, EBS the backing device, set writeback mode, format and mount `/dev/bcache0`.
- Add `bcache-tools` to the Packer AMI build so `make-bcache` is available.

**Non-Goals:**
- Supporting bcache with multiple EBS volumes or multiple NVMe devices beyond the primary pair.
- Choosing cache mode (writethrough vs writeback) at runtime — writeback is fixed as the default.
- Persisting bcache configuration across reboots (test clusters are ephemeral; bcache must be re-registered on reboot, which is out of scope for now).
- Migrating existing clusters to bcache after init.

## Decisions

### 1. Inject bcache flag into setup_instance.sh via TemplateService

**Rationale:** `setup_instance.sh` is a static classpath resource. The bcache path needs to know whether bcache is enabled before it runs. The cleanest mechanism is to convert the static extraction to a TemplateService-based generation that replaces a `__BCACHE_ENABLED__` placeholder with `true` or `false`. This keeps the bcache logic inside the script (where it belongs) while allowing Init to configure it.

`Init.extractResourceFiles()` currently calls `extractResourceFile(...)` to copy the script verbatim. This method will be updated to read the script as a template string and write the substituted version.

**Alternative considered:** Pass `BCACHE_ENABLED=true` as an environment variable prefix when invoking the script (e.g., `sudo BCACHE_ENABLED=true bash setup_instance.sh`). Rejected because it would require modifying `SetupInstance.kt` to read from cluster state, adding coupling between the command and the bcache feature at runtime.

**Alternative considered:** Generate a separate `setup_bcache.sh` script that runs before the main script. Rejected as unnecessary complexity — a single script with a conditional is simpler.

### 2. Validate bcache prerequisites in InstanceSpecFactory

**Rationale:** `DefaultInstanceSpecFactory.createInstanceSpecs()` already enforces the rule "no instance store AND no EBS = fail". bcache adds a new combination constraint: "bcache enabled AND no EBS = fail" and "bcache enabled AND no instance store = fail". The factory already has access to EBS config and instance store detection — it's the right place.

**Alternative considered:** Validate in the `Init` command directly. Rejected; validation belongs in the service layer per the architecture principles.

### 3. bcache device detection in setup_instance.sh

The script needs to identify two devices:
- **NVMe (cache):** The local instance store. Currently `nvme0n1` on most instance types. On instances with multiple NVMe drives, the script uses the first unpartitioned one.
- **EBS (backing):** Attached at `/dev/xvdf` by default (as set in `EBSConfig.deviceName`).

In the bcache path, the device detection logic is inverted: instead of using the first available disk as the single data disk, the script explicitly identifies each role:
- Cache device = first unpartitioned NVMe (`nvme0n1`)
- Backing device = EBS device (`xvdf`, which appears as `/dev/xvdf` or `/dev/nvme1n1` depending on NVMe translation)

A robust detection approach for the EBS device: scan `lsblk` for devices that are EBS-backed (kernel name `xvd*` or the second NVMe device when instance store occupies `nvme0n1`). Since EBS devices attached at `/dev/xvdf` appear as `/dev/xvdf` on Xen-based instances and `/dev/nvme1n1` on Nitro-based instances, the script will use the device name from the `EBSConfig.deviceName` (`/dev/xvdf`) and let the OS resolve it via symlinks (Nitro instances create `/dev/xvdf → /dev/nvme1n1` symlinks via udev rules).

### 4. bcache-tools in Packer AMI

The `make-bcache` utility (from `bcache-tools`) must be available on the instance at setup time. The Packer base provisioning script (`packer/base/install/`) should install `bcache-tools` unconditionally — it's a small package and doesn't hurt instances that don't use bcache.

The `bcache` kernel module is included in the standard Ubuntu kernel (`linux-modules-extra-*`) and must be loaded before use. The setup script will run `modprobe bcache` before any bcache operations.

## Risks / Trade-offs

- **bcache data loss on instance restart:** bcache is a kernel feature that requires re-registration after reboot. Test clusters are typically not rebooted, but if they are, the bcache device will not be available until re-setup. This is documented as a known limitation.
- **Device name variability on Nitro:** EBS devices on Nitro instances appear as `nvme1n1` (not `xvdf`). The udev symlink (`/dev/xvdf → /dev/nvme1n1`) created by the AWS NVMe driver resolves this, but depends on the driver being active. This is standard AWS behavior and reliable in practice.
- **bcache kernel module availability:** bcache is included in `linux-modules-extra-aws` on Ubuntu. The Packer AMI must ensure this package is installed. If missing, `modprobe bcache` will fail with a clear error.
- **No bcache support without both devices:** If a user specifies `--bcache` but chooses an instance type without NVMe (e.g., `c5.large`), the error must be clear at `init` time, not at `up` time.
