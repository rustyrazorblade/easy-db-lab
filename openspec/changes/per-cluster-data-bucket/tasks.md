## 1. ClusterState and Constants

- [ ] 1.1 Add `dataBucket: String` field to `ClusterState`
- [ ] 1.2 Add `Constants.S3.DATA_BUCKET_PREFIX` = `"easy-db-lab-data-"`
- [ ] 1.3 Add `ClusterState.dataBucketName()` method that returns `"easy-db-lab-data-$clusterId"`

## 2. S3 Data Bucket Service Methods

- [ ] 2.1 Add `createDataBucket(bucketName)` to `AwsS3BucketService` — creates bucket, tags with `easy_cass_lab=1`, `cluster_id`, `cluster_name`
- [ ] 2.2 Add `putDataBucketPolicy(bucketName)` — attach IAM role access policy to data bucket
- [ ] 2.3 Add `findDataBuckets()` to `AwsS3BucketService` — find all buckets with `easy_cass_lab=1` tag and `easy-db-lab-data-` prefix
- [ ] 2.4 Add `deleteEmptyBucket(bucketName)` to `AwsS3BucketService` — delete a bucket (fails gracefully if non-empty)
- [ ] 2.5 Add `setFullBucketLifecycleExpiration(bucketName, days)` — lifecycle rule on entire bucket (no prefix filter)
- [ ] 2.6 Add corresponding methods to `AWS` provider if needed

## 3. IAM Policy Update

- [ ] 3.1 Update EC2 role IAM policy to grant `s3:*` on `arn:aws:s3:::easy-db-lab-data-*` and `arn:aws:s3:::easy-db-lab-data-*/*`

## 4. Domain Events

- [ ] 4.1 Add `Event.S3.DataBucketCreating(bucketName)` and `Event.S3.DataBucketCreated(bucketName)` events
- [ ] 4.2 Add `Event.S3.DataBucketExpiring(bucketName, days)` event for lifecycle expiration
- [ ] 4.3 Add `Event.S3.DataBucketDeleting(bucketName)` and `Event.S3.DataBucketDeleted(bucketName)` events

## 5. Up Command — Data Bucket Creation

- [ ] 5.1 In `Up.configureAccountS3Bucket()` (or new method), create the data bucket using `ClusterState.dataBucketName()`
- [ ] 5.2 Store data bucket name in `ClusterState.dataBucket`
- [ ] 5.3 Move CloudWatch S3 request metrics configuration from account bucket to data bucket
- [ ] 5.4 Test: verify data bucket is created and tagged during provisioning

## 6. ClickHouse S3 Endpoint

- [ ] 6.1 Update `ClusterS3Path.clickhouse()` (or ClickHouse config code) to resolve against the data bucket
- [ ] 6.2 Update `K8sService.createClickHouseS3ConfigMap()` to use data bucket endpoint
- [ ] 6.3 Test: verify ClickHouse S3 endpoint points to data bucket

## 7. Down Command — Lifecycle Expiration

- [ ] 7.1 On `down` (single cluster): set lifecycle expiration on the data bucket for all objects (using `--retention-days`)
- [ ] 7.2 On `down` (single cluster): disable CloudWatch metrics on the data bucket (not account bucket)
- [ ] 7.3 On `down --all`: find all data buckets via tags, set lifecycle expiration on each, then attempt deletion
- [ ] 7.4 Test: verify `down` sets lifecycle expiration on data bucket
- [ ] 7.5 Test: verify `down --all` discovers and handles data buckets

## 8. Grafana S3 CloudWatch Dashboard

- [ ] 8.1 Update `TemplateService.buildContextVariables()` so `BUCKET_NAME` resolves to the data bucket instead of the account bucket, for the S3 CloudWatch dashboard
- [ ] 8.2 Verify `METRICS_FILTER_ID` still resolves correctly against the data bucket's metrics config
- [ ] 8.3 Test: verify s3-cloudwatch dashboard template substitution points to data bucket

## 9. Documentation

- [ ] 8.1 Update `docs/` user-facing documentation for the new data bucket behavior
- [ ] 8.2 Update `CLAUDE.md` or subdirectory CLAUDE.md files if S3 patterns change
