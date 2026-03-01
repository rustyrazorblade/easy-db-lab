## MODIFIED Requirements

### Requirement: OTel Java agent installed on EMR nodes via bootstrap action

The system SHALL upload a bootstrap action script to S3 and configure EMR cluster creation to run it, installing the OTel Java agent JAR (`/opt/otel/opentelemetry-javaagent.jar`), the OTel Collector binary (`/opt/otel/otelcol-contrib`), and the Pyroscope Java agent JAR (`/opt/pyroscope/pyroscope.jar`) on every EMR node, and start the OTel Collector as a systemd service. The agents are activated cluster-wide via the `spark-defaults` EMR classification (not per-job submission).

#### Scenario: Bootstrap script uploaded before cluster creation

- **WHEN** the user creates an EMR cluster
- **THEN** a bootstrap script (`bootstrap-otel.sh`) is uploaded to S3 at `clusters/{name}-{id}/spark/bootstrap-otel.sh` before `createCluster()` is called

#### Scenario: Bootstrap action runs on all EMR nodes

- **WHEN** the EMR cluster starts
- **THEN** the bootstrap script installs:
  1. OTel Java agent v2.25.0 JAR to `/opt/otel/opentelemetry-javaagent.jar`
  2. OTel Collector contrib binary to `/opt/otel/otelcol-contrib`
  3. Pyroscope Java agent JAR to `/opt/pyroscope/pyroscope.jar`
  4. OTel Collector config to `/opt/otel/config.yaml` with control node IP
  5. Systemd unit for `otel-collector.service`, started and enabled

#### Scenario: Bootstrap action receives control node IP

- **WHEN** the bootstrap script is invoked by EMR
- **THEN** the control node's private IP is passed as a script argument and used in the OTel Collector config's OTLP exporter endpoint

#### Scenario: EMRClusterConfig supports bootstrap actions

- **WHEN** `EMRService.createCluster()` is called with an `EMRClusterConfig` containing `bootstrapActions`
- **THEN** the bootstrap actions are attached to the `RunJobFlowRequest` and executed during cluster bootstrap

#### Scenario: Agents activated via spark-defaults classification

- **WHEN** `EMRService.createCluster()` is called with an `EMRClusterConfig` containing `configurations`
- **THEN** the `spark-defaults` classification activates the installed OTel and Pyroscope agents for all Spark JVMs via `spark.driver.extraJavaOptions` and `spark.executor.extraJavaOptions`
