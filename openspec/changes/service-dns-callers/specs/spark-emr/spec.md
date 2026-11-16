## MODIFIED Requirements

### Requirement: Pyroscope agent attached to Spark driver and executor JVMs

The system SHALL inject Pyroscope Java agent flags into Spark submit arguments so that driver and executor JVMs send continuous profiles to the cluster's Pyroscope. EMR nodes are outside the cluster and cannot resolve cluster DNS, so the address SHALL be the control node's private IP at the Pyroscope NodePort, or the telemetry redirect's profiles endpoint when the cluster redirects.

#### Scenario: Spark submit includes Pyroscope agent flags

- **WHEN** a Spark job is submitted via `EMRSparkService`
- **THEN** `spark.driver.extraJavaOptions` and `spark.executor.extraJavaOptions` include `-javaagent:/opt/pyroscope/pyroscope.jar` with Pyroscope system properties

#### Scenario: Pyroscope agent configured with correct server address

- **WHEN** a Spark job is submitted on a cluster with no telemetry redirect
- **THEN** the Pyroscope agent is configured with `pyroscope.server.address=http://<control_ip>:<Pyroscope NodePort>`
