## Why

The Cassandra Sidecar runs as a separate Java process on every Cassandra node but has no observability instrumentation. It handles critical operations (SSTable uploads, blob restores, schema management) but we can't see its JVM metrics, traces, or CPU/allocation profiles. Adding OTel and Pyroscope Java agents — matching how Cassandra itself is instrumented — gives us full visibility into sidecar performance and behavior.

## What Changes

- Install the OpenTelemetry Java agent JAR during Packer AMI build (new install script)
- Add OTel and Pyroscope `-javaagent` flags to the sidecar's systemd service `JAVA_OPTS`
- Configure the OTel agent to export traces and JVM metrics to the local OTel Collector DaemonSet on `localhost:4317` (OTLP gRPC)
- Configure the Pyroscope agent to send profiles to the Pyroscope server on the control node
- Gate activation on environment variables (same pattern as Cassandra: `PYROSCOPE_SERVER_ADDRESS` from `/etc/default/cassandra`)

## Capabilities

### New Capabilities

- `sidecar-otel`: OTel and Pyroscope Java agent instrumentation for the Cassandra Sidecar process

### Modified Capabilities

None — this adds instrumentation to an existing process without changing its behavior or the observability spec requirements.

## Impact

- `packer/cassandra/` — new install script for OTel Java agent JAR, modified sidecar systemd service
- `packer/cassandra/cassandra.pkr.hcl` — add OTel agent install step to build
- `packer/cassandra/services/cassandra-sidecar.service` — add `-javaagent` flags to JAVA_OPTS
- No Kotlin code changes expected — the sidecar service file is baked into the AMI
- New AMI build required to pick up changes
