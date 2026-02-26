## Why

The SQS-based log ingestion pipeline (S3 event notifications -> SQS -> Vector S3 Deployment) was added for EMR/Spark log collection but has become a reliability problem. The S3 bucket notification configuration and validation step fails during `up`, blocking cluster provisioning entirely. SQS adds complexity (IAM policies, queue lifecycle, notification wiring, a separate Vector deployment) for marginal value.

Vector itself is now redundant. Its ClickHouse log collection duplicates what OTel already handles with proper JSON parsing. Its system log, Cassandra log, and journald collection can be migrated to OTel receivers. Removing both SQS and Vector simplifies the observability stack to a single log collection agent (OTel).

## What Changes

### SQS Removal
- **BREAKING**: Remove SQS queue creation during `up` and deletion during `down`
- Remove `SQSService` interface and `AWSSQSService` implementation
- Remove `SqsClient` and `SQSService` Koin registrations from `AWSModule`
- Remove `Event.Sqs.*` domain events and `Event.Provision.LogPipelineValidating`/`LogPipelineValid`
- Remove `sqsQueueUrl`/`sqsQueueArn` fields from `ClusterState` and `updateSqsQueue()` method
- Remove `S3ObjectStore.configureEMRLogNotifications()` and `getBucketNotificationConfiguration()`
- Remove SQS permissions from IAM policies (`iam-policy-iam-s3.json` and `AWSPolicy.Inline.S3AccessWildcard`)
- Remove `sqs_queue_url` from the `cluster-config` ConfigMap in `GrafanaUpdateConfig`
- Remove `awssdk-sqs` dependency from Gradle

### Vector Removal
- Delete `VectorManifestBuilder` entirely
- Delete `vector-node.yaml` and `vector-s3.yaml` resource files
- Remove Vector from `GrafanaUpdateConfig` (K8s resource deployment)
- Remove `vector-node` and `vector-s3` Prometheus scrape jobs from OTel collector config
- Remove Vector image from K8s integration test image pull checks

### OTel Migration (replace Vector's unique log sources)
- Add `filelog/system` receiver to OTel for `/var/log/**/*.log`, `/var/log/messages`, `/var/log/syslog`
- Add `filelog/cassandra` receiver to OTel for `/mnt/db1/cassandra/logs/*.log`
- Add `journald` receiver to OTel for cassandra.service, docker.service, k3s.service, sshd.service
- Add corresponding hostPath volume mounts to `OtelManifestBuilder` (`/var/log`, `/mnt/db1/cassandra/logs`, journal directory)
- Wire new receivers into OTel's log pipeline with appropriate attributes

### Tests, Scripts, Docs
- Update tests: `DownCommandTest` (remove SQS mock), `K8sServiceIntegrationTest` (remove `sqs_queue_url` from ConfigMap, remove Vector resources)
- Remove SQS references from `bin/debug-log-pipeline` and `bin/test-spark-bulk-writer` scripts
- Update documentation: `log-infrastructure.md`, `victoria-logs.md`

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `cluster-lifecycle`: Remove SQS queue creation from `up` and SQS queue deletion from `down`
- `observability`: Remove Vector entirely; migrate system logs, Cassandra logs, and journald collection to OTel
- `spark-emr`: EMR log collection via SQS is removed entirely

## Impact

- **Commands**: `Up`, `Down`, `GrafanaUpdateConfig`
- **Services**: `SQSService` (deleted), `S3ObjectStore` (methods removed), `AWSModule` (Koin registrations removed)
- **Configuration**: `ClusterState`, `VectorManifestBuilder` (deleted), `vector-node.yaml` (deleted), `vector-s3.yaml` (deleted), `OtelManifestBuilder` (new volume mounts), `otel-collector-config.yaml` (new receivers, removed Vector scrape jobs), `cluster-config` ConfigMap
- **IAM**: SQS permissions removed from user policy and EC2 instance role inline policy
- **Events**: `Event.Sqs` interface removed, two `Event.Provision` log pipeline events removed
- **Tests**: `DownCommandTest`, `K8sServiceIntegrationTest`
- **Scripts**: `bin/debug-log-pipeline`, `bin/test-spark-bulk-writer`
- **Docs**: `log-infrastructure.md`, `victoria-logs.md`
- **Dependencies**: `awssdk-sqs` removed from Gradle
