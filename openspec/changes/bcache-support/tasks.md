## 1. CLI and Configuration

- [ ] 1.1 Add `--bcache` boolean flag (default: `false`) to `Init.kt`
- [ ] 1.2 Add `bcache: Boolean = false` field to `InitConfig` in `ClusterState.kt`
- [ ] 1.3 Update `InitConfig.fromInit()` to populate `bcache` from the flag

## 2. Validation

- [ ] 2.1 Add bcache prerequisite validation to `DefaultInstanceSpecFactory.createInstanceSpecs()`:
  - If `bcache = true` and `ebsType == "NONE"`: throw `IllegalArgumentException` with message indicating that `--bcache` requires `--ebs.type` to be set
  - If `bcache = true` and instance type has no instance store: throw `IllegalArgumentException` with message indicating that `--bcache` requires an instance type with local NVMe instance store
- [ ] 2.2 Write unit tests for the new validation scenarios:
  - `--bcache` + EBS + instance store → passes
  - `--bcache` + no EBS → fails with clear error
  - `--bcache` + no instance store → fails with clear error
  - `--bcache` disabled (default) → existing validation unchanged

## 3. Script Generation

- [ ] 3.1 Convert `setup_instance.sh` to a TemplateService template by adding a `__BCACHE_ENABLED__` placeholder in the CONFIGURATION block (e.g., `export BCACHE_ENABLED=__BCACHE_ENABLED__`)
- [ ] 3.2 Update `Init.extractResourceFiles()` to use `TemplateService` for script generation, substituting `BCACHE_ENABLED` with `"true"` or `"false"` based on `InitConfig.bcache`
- [ ] 3.3 Add bcache setup logic to `setup_instance.sh` in a clearly delineated bcache section:
  - `modprobe bcache`
  - Identify cache device (first local NVMe, e.g., `/dev/nvme0n1`)
  - Identify backing device (EBS device path from config, e.g., `/dev/xvdf`)
  - Run `make-bcache -C <cache_device>` and `make-bcache -B <backing_device>`
  - Attach cache to backing: `echo <cset_uuid> > /sys/block/bcache0/bcache/attach`
  - Set writeback mode: `echo writeback > /sys/block/bcache0/bcache/cache_mode`
  - Set `DISK=/dev/bcache0` for the subsequent format and mount steps
- [ ] 3.4 Verify the non-bcache path in `setup_instance.sh` is unaffected when `BCACHE_ENABLED=false`

## 4. AMI / Packer

- [ ] 4.1 Add `bcache-tools` and `linux-modules-extra-aws` (if not already present) to the Packer base install script
- [ ] 4.2 Verify the packer test infrastructure (`testPackerBase`) still passes with the added packages

## 5. Documentation

- [ ] 5.1 Add a `--bcache` section to the `docs/` init command reference (prerequisites, use case, caveats about bcache not surviving reboots)
- [ ] 5.2 Note the instance type requirements (must have local NVMe instance store) and EBS requirement

## 6. Specs

- [ ] 6.1 Create `openspec/specs/bcache/spec.md` with formal requirements for bcache support
- [ ] 6.2 Update `openspec/specs/instance-storage-validation/spec.md` to add bcache validation scenarios
