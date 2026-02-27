## ADDED Requirements

### Requirement: Pyroscope Java agent installed on EMR nodes

The system SHALL install the Pyroscope Java agent JAR on every EMR node via the bootstrap action.

#### Scenario: Pyroscope agent installed during bootstrap

- **WHEN** the EMR cluster starts and bootstrap actions execute
- **THEN** the Pyroscope Java agent JAR is downloaded and installed at `/opt/pyroscope/pyroscope.jar`

### Requirement: Pyroscope agent attached to Spark driver and executor JVMs

The system SHALL inject Pyroscope Java agent flags into Spark submit arguments so that driver and executor JVMs send continuous profiles to the Pyroscope server on the control node.

#### Scenario: Spark submit includes Pyroscope agent flags

- **WHEN** a Spark job is submitted via `EMRSparkService`
- **THEN** `spark.driver.extraJavaOptions` and `spark.executor.extraJavaOptions` include `-javaagent:/opt/pyroscope/pyroscope.jar` with Pyroscope system properties

#### Scenario: Pyroscope agent configured with correct server address

- **WHEN** a Spark job is submitted
- **THEN** the Pyroscope agent is configured with `pyroscope.server.address=http://<control_ip>:4040`

#### Scenario: Pyroscope agent collects CPU, allocation, and lock profiles

- **WHEN** a Spark job is running with the Pyroscope agent
- **THEN** the agent collects CPU profiles, allocation profiles (512k threshold), and lock contention profiles (10ms threshold) in JFR format

#### Scenario: Profiles labeled with job identity

- **WHEN** a Spark job is running with the Pyroscope agent
- **THEN** profiles are labeled with `application.name=spark-<jobName>`, `hostname`, and `cluster` for identification in Pyroscope UI
