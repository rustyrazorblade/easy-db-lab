## 1. ClusterState and Constants

- [x] 1.1 Add `dataBucket: String` field to `ClusterState`
- [x] 1.2 Add `Constants.S3.DATA_BUCKET_PREFIX` = `"easy-db-lab-data-"`
- [x] 1.3 Add `ClusterState.dataBucketName()` method that returns `"easy-db-lab-data-$clusterId"`

## 2. S3 Data Bucket Service Methods

- [x] 2.1 Reuse existing `createBucket`, `putBucketPolicy`, `tagBucket` for data bucket creation (done in Up command)
- [x] 2.3 Add `findDataBuckets()` to `AwsS3BucketService` and `AWS` provider
- [x] 2.4 Add `deleteEmptyBucket(bucketName)` to `AwsS3BucketService` and `AWS` provider
- [x] 2.5 Add `setFullBucketLifecycleExpiration(bucketName, days)` to `AwsS3BucketService` and `AWS` provider

## 3. IAM Policy Update

- [x] 3.1 Already covered — existing wildcard policy `easy-db-lab-*` covers `easy-db-lab-data-*` buckets

## 4. Domain Events

- [x] 4.1 Add `Event.S3.DataBucketCreating(bucketName)` and `Event.S3.DataBucketCreated(bucketName)` events
- [x] 4.2 Add `Event.S3.DataBucketExpiring(bucketName, days)` event for lifecycle expiration
- [x] 4.3 Add `Event.S3.DataBucketDeleting(bucketName)` and `Event.S3.DataBucketDeleted(bucketName)` events

## 5. Up Command — Data Bucket Creation

- [x] 5.1 In `Up.configureAccountS3Bucket()`, create data bucket using `ClusterState.dataBucketName()`
- [x] 5.2 Store data bucket name in `ClusterState.dataBucket`
- [x] 5.3 Move CloudWatch S3 request metrics from account bucket to data bucket

## 6. ClickHouse S3 Endpoint

- [x] 6.1 Update `ClickHouseStart.setupS3Secret()` to use `dataBucket` with `clickhouse/` prefix
- [x] 6.2 `K8sService.createClickHouseS3ConfigMap()` unchanged — receives endpoint URL from caller

## 7. Down Command — Lifecycle Expiration

- [x] 7.1 On `down` (single cluster): set lifecycle expiration on data bucket via `setDataBucketLifecycleExpiration()`
- [x] 7.2 On `down` (single cluster): disable CloudWatch metrics on data bucket (not account bucket)
- [x] 7.3 On `down --all`: `teardownAllDataBuckets()` finds all data buckets, sets lifecycle expiration, attempts deletion

## 8. Grafana S3 CloudWatch Dashboard

- [x] 8.1 Update `TemplateService.buildContextVariables()` — `BUCKET_NAME` resolves to `dataBucket` (falls back to `s3Bucket`)
- [x] 8.2 `METRICS_FILTER_ID` unchanged — still uses `metricsConfigId()` which is cluster-scoped

## 9. Documentation

- [x] 9.1 Update `configuration/CLAUDE.md` — added `dataBucket` field, `dataBucketName()` method, updated `BUCKET_NAME` context variable docs
- [ ] 9.2 Update `docs/` user-facing documentation for the new data bucket behavior

## Tests

- [x] `ClusterStateTest` — `dataBucketName()`, default value, save/load roundtrip
- [x] `TemplateServiceTest` — `BUCKET_NAME` resolves to `dataBucket` when set, falls back to `s3Bucket`
- [x] `ClickHouseStartTest` — updated mock `ClusterState` to include `dataBucket`
- [x] `EventSerializationTest` — new events serialize correctly (automatic via sealed hierarchy)
- [x] All existing tests pass (1405/1407 pass, 2 pre-existing K3s TestContainers flakes)
