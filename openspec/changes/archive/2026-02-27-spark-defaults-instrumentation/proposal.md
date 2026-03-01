## Why

OTel and Pyroscope Java agent JARs are installed on every EMR node via bootstrap action, but they're only activated per-job at `spark-submit` time via `EMRSparkService.buildOtelSparkConf()`. This means Spark processes not submitted through our tooling (or YARN infrastructure processes) don't get instrumented. Moving the agent flags and OTEL environment variables to the EMR `spark-defaults` classification ensures all Spark JVMs are always instrumented from cluster startup.

## What Changes

- Add EMR classification support to `EMRClusterConfig` and `EMRService.createCluster()` so configurations can be passed to `RunJobFlowRequest`
- Build `spark-defaults` classification in `EMRProvisioningService` at cluster creation time with OTel/Pyroscope agent flags and OTEL environment variables
- Simplify `EMRSparkService` per-job enrichment to only override job-specific names (`OTEL_SERVICE_NAME`, `pyroscope.application.name`) — agent loading and exporter config come from spark-defaults
- Update delta specs for `spark-emr` and `emr-otel-bootstrap` to reflect the new instrumentation source

## Capabilities

### New Capabilities

- `emr-spark-defaults`: EMR cluster creation includes `spark-defaults` classification with OTel/Pyroscope instrumentation properties applied cluster-wide

### Modified Capabilities

- `spark-emr`: Per-job submission no longer injects agent flags — only overrides job-specific service name and profiler application name
- `emr-otel-bootstrap`: Bootstrap action still installs agent JARs; the new `spark-defaults` classification activates them for all Spark JVMs

## Impact

- `EMRTypes.kt` — new `EMRConfiguration` data class, new field on `EMRClusterConfig`
- `EMRService.kt` — apply configurations to `RunJobFlowRequest`
- `EMRProvisioningService.kt` — build spark-defaults configuration with agent flags and OTEL env vars
- `EMRSparkService.kt` — simplify `buildOtelSparkConf()` and `buildOtelEnvVars()` to name-only overrides
- Tests for all modified services
