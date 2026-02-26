## Why

Running multiple lab clusters concurrently hits S3 limitations when all clusters share a single account bucket. Request rate throttling and metrics configuration conflicts cause CloudWatch S3 request metrics to break. A per-cluster data bucket isolates each lab's high-volume data (ClickHouse, CloudWatch metrics) while keeping the shared account bucket for low-volume configuration and backups.

## What Changes

- Create a per-cluster S3 bucket named `easy-db-lab-data-<cluster-id>` during cluster provisioning (`up`)
- Move ClickHouse S3 storage to use the data bucket instead of a prefix in the account bucket
- Configure CloudWatch S3 request metrics on the data bucket instead of the account bucket
- On `down`: set a lifecycle expiration rule on the entire data bucket (not individual file deletes)
- On `down --all`: delete all data buckets (find by tag)
- The account bucket remains unchanged for config, backups, logs, metrics snapshots, and other low-volume data

## Capabilities

### New Capabilities

_None — this extends existing capabilities._

### Modified Capabilities

- `cluster-lifecycle`: Provisioning creates a per-cluster data bucket; teardown marks it for expiration or deletes it
- `clickhouse`: ClickHouse S3 storage endpoint changes from account bucket prefix to per-cluster data bucket

## Impact

- **S3 bucket service**: New methods to create/tag/expire/delete per-cluster data buckets
- **ClusterState**: Stores the data bucket name
- **Up command**: Creates data bucket, configures CloudWatch metrics on it
- **Down command**: Sets lifecycle expiration on data bucket; `--all` deletes data buckets
- **ClickHouse config**: S3 endpoint URL changes to use data bucket
- **IAM policies**: EC2 role needs access to `easy-db-lab-data-*` buckets
- **Events**: New events for data bucket creation, expiration, deletion
