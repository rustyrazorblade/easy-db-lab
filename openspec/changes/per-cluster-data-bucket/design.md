## Context

All lab clusters currently share a single account-level S3 bucket (`easy-db-lab-<uuid>`) with cluster-specific prefixes (`clusters/{name}-{id}/`). ClickHouse data, CloudWatch S3 request metrics, and all other cluster data live under these prefixes.

Running 2+ labs concurrently causes two problems:
1. **CloudWatch conflict**: Concurrent modifications to the bucket's metrics configuration fail — two clusters enabling/disabling S3 request metrics on the same bucket race against each other.
2. **Noisy neighbor**: High-volume ClickHouse I/O from one lab can throttle S3 request rates for another lab sharing the same bucket.

The account bucket (`easy-db-lab-<uuid>`) will continue to exist for low-volume data (config backups, kubeconfig, cassandra patches, observability snapshots). A new per-cluster data bucket isolates high-volume and CloudWatch-sensitive workloads.

## Goals / Non-Goals

**Goals:**
- Eliminate CloudWatch metrics configuration conflicts between concurrent labs
- Eliminate S3 request rate noisy-neighbor effects for data-heavy workloads
- Per-cluster data buckets are created during `up` and expired during `down`
- `down --all` finds and deletes all data buckets
- Bulk data cleanup uses lifecycle expiration, not individual object deletes

**Non-Goals:**
- Migrating existing account bucket data (config, backups, logs, metrics snapshots) to per-cluster buckets
- Changing how the account bucket is created or managed
- Supporting data bucket reuse across cluster recreations

## Decisions

### 1. Bucket naming: `easy-db-lab-data-<cluster-id>`

Use the cluster UUID as the bucket suffix. This guarantees uniqueness without name collisions. The `easy-db-lab-data-` prefix makes buckets discoverable via listing or tagging.

**Alternative considered**: Using `easy-db-lab-data-<name>-<id>`. Rejected because S3 bucket names have a 63-character limit and cluster names can be long. The UUID alone is sufficient for uniqueness, and the bucket is tagged with the cluster name for human identification.

### 2. Bucket tagging for discovery

Tag data buckets with `easy_cass_lab=1` (matching existing convention) plus `cluster_id=<id>` and `cluster_name=<name>`. The `down --all` command finds data buckets the same way it finds VPCs — by tag.

### 3. Lifecycle expiration for teardown

On `down`, apply a lifecycle rule that expires all objects in the data bucket after `--retention-days` (default 1). This matches the existing pattern used for cluster prefixes in the account bucket. The bucket itself is not deleted immediately — S3 will empty it via lifecycle, and a future `down --all` or manual cleanup can delete the empty bucket.

**Alternative considered**: Deleting the bucket immediately. Rejected because non-empty bucket deletion requires listing and deleting all objects first, which is exactly the problem the user wants to avoid with large ClickHouse datasets.

On `down --all`, delete data buckets outright. Since `--all` is a full cleanup operation, it should not leave stale buckets behind. Apply lifecycle expiration first (to handle objects), then schedule or attempt bucket deletion. If the bucket is non-empty (lifecycle hasn't completed), tag it for deletion and warn the user.

### 4. CloudWatch metrics on data bucket

Move S3 request metrics configuration from the account bucket to the data bucket. Each cluster's metrics config lives on its own bucket, eliminating concurrent modification conflicts entirely.

### 5. ClickHouse S3 endpoint

Point ClickHouse at the data bucket root instead of a prefix in the account bucket. The `ClusterS3Path` for ClickHouse will resolve to `https://easy-db-lab-data-<id>.s3.<region>.amazonaws.com/clickhouse/`.

### 6. IAM policy update

The EC2 instance role policy needs `s3:*` access to `arn:aws:s3:::easy-db-lab-data-*` in addition to the existing account bucket permissions. This is a wildcard on the naming convention, not per-bucket.

### 7. ClusterState stores data bucket name

Add `dataBucket: String` to `ClusterState`. Non-nullable as backwards compatibility is not a priority.

## Risks / Trade-offs

- **More S3 buckets per account**: AWS has a default limit of 100 buckets per account (can be raised to 1000). Labs are ephemeral so buckets are cleaned up, but users running many concurrent labs should be aware. → Mitigation: `down --all` cleans up data buckets; lifecycle expiration prevents orphaned data.

- **Bucket deletion timing**: After `down`, the bucket exists until lifecycle expiration completes and someone deletes the empty bucket. → Mitigation: `down --all` handles cleanup; buckets tagged for identification.

- **Backward compatibility**: Existing clusters have no data bucket. →  This isn't a concern, the clusters are never reused.  The project does not have a general requirement to keep things backwards compatible.
