## Why

When a cluster is initialized with an instance type that has no local NVMe instance store (e.g., `c5.2xlarge`) and no `--ebs.type` is specified, the `setup_instance.sh` script silently fails to mount any data disk. All database data ends up on the 16GB root partition, which will fill up and cause failures. The tool should fail fast at init time instead of silently creating a broken cluster.

## What Changes

- Add instance store detection using the AWS EC2 `DescribeInstanceTypes` API to check whether the selected instance type includes local NVMe storage.
- Validate at init time that the instance type either has instance store OR `--ebs.type` is specified. Fail with a clear error if neither condition is met.
- The validation applies to Cassandra (db) nodes only — stress and control nodes don't need data disks.

## Capabilities

### New Capabilities

- `instance-storage-validation`: Validate that Cassandra instances have storage (instance store or EBS) before provisioning.

### Modified Capabilities

_(none)_

## Impact

- **Init command**: Will now fail fast if storage is insufficient.
- **EC2 service layer**: New `DescribeInstanceTypes` API call to query instance store info.
- **User experience**: Users with non-instance-store types must now pass `--ebs.type`. Previously this silently broke; now it gives a clear error.
