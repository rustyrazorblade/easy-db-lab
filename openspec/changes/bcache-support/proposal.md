## Why

Database performance on AWS is a constant tradeoff: EBS volumes provide durable, persistent storage that survives instance termination, but they are limited by network throughput and latency. Local NVMe instance store drives are orders of magnitude faster for random I/O, but their data is lost when the instance stops. For test workloads — where data persistence between runs is not required — the performance difference is significant.

bcache is a Linux kernel block-layer caching mechanism that allows a fast SSD (the cache device) to transparently accelerate a slower block device (the backing device). By using local NVMe as a write-back cache in front of an EBS volume, the cluster gets EBS-level persistence with NVMe-level I/O performance for hot data. This is particularly useful for database workloads that have active working sets smaller than the NVMe device, and where test reruns can tolerate cache misses on cold start.

## What Changes

- Add `--bcache` flag to the `init` command. When enabled, configures the local NVMe instance store as a write-back bcache cache device in front of the EBS volume.
- Validate at init time that `--bcache` requires both `--ebs.type` (not NONE) and an instance type with local NVMe instance store. Fail fast with a clear error if either condition is missing.
- Modify `setup_instance.sh` generation to support bcache configuration: detect the NVMe device (cache) and the EBS device (backing), register them with bcache, and expose the resulting `/dev/bcache0` device for XFS formatting and mounting to `/mnt/db1`.
- Store `bcache: Boolean` in `InitConfig` (persisted to `state.json`) so the cluster's storage topology is part of its saved configuration.

## Capabilities

### New Capabilities

- `bcache`: Configure Linux bcache write-back caching using local NVMe as cache and EBS as backing device, exposed as a single `/dev/bcacheN` virtual block device.

### Modified Capabilities

- `instance-storage-validation`: Add validation scenarios for the `--bcache` flag — it requires both EBS and local instance store to be present.

## Impact

- **Init command**: New `--bcache` boolean flag.
- **InitConfig**: New `bcache: Boolean = false` field.
- **InstanceSpecFactory**: Additional validation when `bcache = true`.
- **setup_instance.sh**: New bcache setup path (NVMe → cache device, EBS → backing device, mount `/dev/bcache0`).
- **Script generation in Init**: Use TemplateService to inject `BCACHE_ENABLED` into the script, replacing the static `extractResourceFile` call.
- **Packer AMI**: The `bcache-tools` package must be installed so `make-bcache` is available on instance start.
- **Docs**: New section on `--bcache` flag with prerequisites, use cases, and caveats.
- **Tests**: Unit tests for InstanceSpecFactory bcache validation; packer script test for bcache setup path.
