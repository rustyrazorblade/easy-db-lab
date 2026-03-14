## 1. CLI and Configuration

- [x] 1.1 Add `--bcache` boolean flag (default: `false`) to `Init.kt`
- [x] 1.2 Add `--bcache.mode` string flag (default: `"writethrough"`, allowed values: `writethrough`, `writeback`) to `Init.kt`; validate the value is one of the two allowed options at parse time
- [x] 1.3 Add `bcache: Boolean = false` and `bcacheMode: String = "writethrough"` fields to `InitConfig` in `ClusterState.kt`
- [x] 1.4 Update `InitConfig.fromInit()` to populate `bcache` and `bcacheMode` from the flags

## 2. Validation

- [x] 2.1 Add bcache prerequisite validation to `DefaultInstanceSpecFactory.createInstanceSpecs()`:
  - If `bcache = true` and `ebsType == "NONE"`: throw `IllegalArgumentException` with message indicating that `--bcache` requires `--ebs.type` to be set
  - If `bcache = true` and instance type has no instance store: throw `IllegalArgumentException` with message indicating that `--bcache` requires an instance type with local NVMe instance store
- [x] 2.2 Write unit tests for the new validation scenarios:
  - `--bcache` + EBS + instance store → passes (writethrough default)
  - `--bcache` + EBS + instance store + `--bcache.mode=writeback` → passes
  - `--bcache` + EBS + instance store + `--bcache.mode=writethrough` → passes
  - `--bcache` + no EBS → fails with clear error
  - `--bcache` + no instance store → fails with clear error
  - `--bcache` + invalid mode value → fails with clear error listing accepted values
  - `--bcache` disabled (default) → existing validation unchanged

## 3. Script Generation

- [x] 3.1 Convert `setup_instance.sh` to a TemplateService template by adding `__BCACHE_ENABLED__` and `__BCACHE_MODE__` placeholders in the CONFIGURATION block (e.g., `export BCACHE_ENABLED=__BCACHE_ENABLED__` and `export BCACHE_MODE=__BCACHE_MODE__`)
- [x] 3.2 Update `Init.extractResourceFiles()` to use `TemplateService` for script generation, substituting:
  - `__BCACHE_ENABLED__` with `"true"` or `"false"` based on `InitConfig.bcache`
  - `__BCACHE_MODE__` with the value of `InitConfig.bcacheMode` (`writethrough` or `writeback`)
  - `__EBS_DEVICE__` with the value of `EBSConfig.deviceName` (e.g., `/dev/xvdf`) so the script never hardcodes the device path
- [x] 3.3 Add bcache setup logic to `setup_instance.sh` in a clearly delineated bcache section:
  - `modprobe bcache`
  - Identify cache device (first local NVMe, e.g., `/dev/nvme0n1`)
  - Identify backing device from `$EBS_DEVICE` (resolved via udev symlink on Nitro instances)
  - Check for existing bcache superblock on both devices (via `bcache-super-show` or `wipefs`) and wipe if present to ensure idempotency
  - Run `make-bcache -C <cache_device>` (register cache set) and `make-bcache -B <backing_device>` (register backing device)
  - Read cset_uuid from `/sys/fs/bcache/` (e.g., `CSET_UUID=$(ls /sys/fs/bcache/)`) after registering the cache device
  - Attach cache to backing: `echo $CSET_UUID > /sys/block/bcache0/bcache/attach`
  - Set cache mode from config: `echo $BCACHE_MODE > /sys/block/bcache0/bcache/cache_mode`
  - Set `DISK=/dev/bcache0` for the subsequent format and mount steps
- [x] 3.4 Verify the non-bcache path in `setup_instance.sh` is unaffected when `BCACHE_ENABLED=false`

## 4. AMI / Packer

- [x] 4.1 Add `bcache-tools` and `linux-modules-extra-aws` (if not already present) to the Packer base install script
- [ ] 4.2 Verify the packer test infrastructure (`testPackerBase`) still passes with the added packages

## 5. Documentation

- [x] 5.1 Add a `--bcache` section to the `docs/` init command reference (prerequisites, use case, caveats about bcache not surviving reboots)
- [x] 5.2 Note the instance type requirements (must have local NVMe instance store) and EBS requirement

## 6. Specs

- [x] 6.1 Create `openspec/specs/bcache/spec.md` with formal requirements for bcache support
- [x] 6.2 Update `openspec/specs/instance-storage-validation/spec.md` to add bcache validation scenarios
