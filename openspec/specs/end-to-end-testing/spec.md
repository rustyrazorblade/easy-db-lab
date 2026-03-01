## Requirements

### Requirement: End-to-end test runner

The system SHALL provide a bash test runner (`bin/end-to-end-test`) that provisions real AWS infrastructure, deploys services, runs validation steps, and tears down the environment. All steps run regardless of earlier failures; diagnostics are captured for each failure.

#### Scenario: Resilient step execution

- **WHEN** a test step fails
- **THEN** the failure is logged with exit code, step output, pod status, K8s events, and disk usage.
- **AND** If additional `aws` cli commands are available to assist with diagnostics
- **THEN** ensure the diagnostic commands are present
- **AND** execution continues to the next step

#### Scenario: Teardown behavior on failure

- **WHEN** any step has failed during the run
- **THEN** the full failure log is printed at the end
- **AND** the script prompts for confirmation before tearing down (keeping the environment alive for debugging)

#### Scenario: Clean exit on success

- **WHEN** all steps pass
- **THEN** the environment is torn down automatically and the script exits with code 0

### Requirement: Feature flags for optional services

The test runner SHALL support feature flags to enable optional test domains: `--cassandra`, `--spark`, `--clickhouse`, `--opensearch`, `--all`, `--ebs`, `--build`.

#### Scenario: Spark steps skipped when flag not set

- **WHEN** the test is run without `--spark`
- **THEN** Spark-related steps (submit, status, bulk writer) are skipped

#### Scenario: All features enabled

- **WHEN** the test is run with `--all`
- **THEN** all optional service tests (Cassandra, Spark, ClickHouse, OpenSearch) are enabled

### Requirement: Breakpoint and resume support

The test runner SHALL support pausing at specific steps and resuming from a given step number.

#### Scenario: Breakpoint pauses execution

- **WHEN** `--break 5,15` is passed
- **THEN** execution pauses before steps 5 and 15 for manual inspection

#### Scenario: Resume from step

- **WHEN** `--start-step N` is passed
- **THEN** execution begins at step N, skipping all earlier steps

#### Scenario: Wait mode

- **WHEN** `--wait` is passed
- **THEN** all steps run except teardown, then the script waits for confirmation before tearing down

### Requirement: Infrastructure provisioning steps

The test runner SHALL provision a full AWS environment including EC2 instances, VPC, S3 bucket, and K3s Kubernetes cluster.

#### Scenario: Build and initialize

- **WHEN** the infrastructure steps run
- **THEN** the project is built, IAM policies are set, a cluster is initialized with EC2 instances and VPC, kubectl access is configured, and K3s is verified as ready

#### Scenario: VPC and host verification

- **WHEN** infrastructure is provisioned
- **THEN** VPC tags are verified, hosts are listed, and the MCP server status endpoint returns cluster data

### Requirement: S3 backup verification

The test runner SHALL verify that cluster state is backed up to S3 after provisioning.

#### Scenario: Backup contains required files

- **WHEN** the S3 backup step runs
- **THEN** the backup contains kubeconfig, configs, cassandra.patch.yaml, and cassandra-config/

### Requirement: Cassandra validation steps

When `--cassandra` is enabled, the test runner SHALL validate Cassandra deployment, operations, and workload testing.

#### Scenario: Cassandra lifecycle

- **WHEN** Cassandra steps run
- **THEN** Cassandra is set up, backup is verified, restore from VPC is tested, SSH and nodetool work, sidecar is checked, exec command works, and start/stop cycle completes

#### Scenario: Stress testing

- **WHEN** Cassandra is running
- **THEN** stress tests run on both EC2 (direct) and K8s (job) with timeout monitoring

### Requirement: Spark/EMR validation steps

When `--spark` is enabled, the test runner SHALL validate Spark job submission, status checking, and log retrieval on EMR.

#### Scenario: Spark job lifecycle

- **WHEN** Spark steps run
- **THEN** a Spark job is submitted to the EMR cluster, its status is checked, and the bulk writer is tested

### Requirement: ClickHouse validation steps

When `--clickhouse` is enabled, the test runner SHALL validate ClickHouse deployment and connectivity.

#### Scenario: ClickHouse lifecycle

- **WHEN** ClickHouse steps run
- **THEN** ClickHouse is started, test data is inserted and queried, and ClickHouse is stopped

### Requirement: OpenSearch validation steps

When `--opensearch` is enabled, the test runner SHALL validate OpenSearch domain deployment and connectivity.

#### Scenario: OpenSearch lifecycle

- **WHEN** OpenSearch steps run
- **THEN** OpenSearch is started, connectivity is tested, and OpenSearch is stopped

### Requirement: Observability stack validation

The test runner SHALL validate the observability stack including metrics, logs, traces, and dashboards.

#### Scenario: Observability health checks

- **WHEN** the observability test step runs
- **THEN** VictoriaMetrics and VictoriaLogs health endpoints are verified, Grafana datasources are validated, and metric ingestion is confirmed

#### Scenario: Dashboard validation

- **WHEN** the dashboard test step runs
- **THEN** all Grafana dashboards load successfully

#### Scenario: Metrics and logs backup

- **WHEN** the backup test steps run
- **THEN** VictoriaMetrics backup and VictoriaLogs backup complete successfully

#### Scenario: Logs query

- **WHEN** the logs query step runs
- **THEN** the `logs query` command returns results from VictoriaLogs

### Requirement: Error handling with interactive recovery

When a step fails, the test runner SHALL provide interactive recovery options.

#### Scenario: Failure recovery menu

- **WHEN** the test completes with failures
- **THEN** an interactive menu offers: retry from failed step, start a shell session (with rebuild/rerun helpers), tear down environment, or exit

### Requirement: Step listing

The test runner SHALL support listing all steps without executing them.

#### Scenario: List steps

- **WHEN** `--list-steps` or `-l` is passed
- **THEN** all steps are printed with their numbers and names, and the script exits without running any steps
