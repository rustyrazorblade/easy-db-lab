## Context

OTel and Pyroscope Java agent JARs are installed on every EMR node via the bootstrap action, but the `-javaagent` flags that activate them are only injected per-job in `EMRSparkService.buildOtelSparkConf()`. This means instrumentation only works for jobs submitted through our CLI, and YARN infrastructure JVMs are never instrumented.

EMR supports passing `configurations` (classifications) at cluster creation time via the `RunJobFlowRequest`. A `spark-defaults` classification sets properties in `/etc/spark/conf/spark-defaults.conf` on all nodes, automatically applying to every Spark application.

## Goals / Non-Goals

**Goals:**
- All Spark JVMs (driver, executor, YARN app master) are instrumented with OTel and Pyroscope agents from cluster startup
- Per-job submission still overrides `OTEL_SERVICE_NAME` and `pyroscope.application.name` with job-specific names
- EMR configuration support is generic (not hardcoded to spark-defaults) so other classifications can be added later

**Non-Goals:**
- Changing what the bootstrap action installs (JARs and OTel Collector stay the same)
- Adding new observability features beyond moving instrumentation activation to cluster-level

## Decisions

**1. Use EMR `configurations` on `RunJobFlowRequest` (not bootstrap script env vars)**

The `RunJobFlowRequest.configurations()` API accepts `Classification` objects that map directly to Hadoop/Spark config files. Using `spark-defaults` classification is the standard EMR approach for cluster-wide Spark properties. This is more reliable than exporting env vars in the bootstrap script because EMR manages the config file lifecycle.

**2. Single `spark-defaults` classification for both JVM flags and env vars**

Spark supports `spark.driverEnv.*`, `spark.executorEnv.*`, and `spark.yarn.appMasterEnv.*` properties in spark-defaults.conf. This avoids needing a separate `spark-env` classification with export statements. All configuration goes through one classification.

**3. Keep per-job name override in `EMRSparkService.submitJob()`**

The default service name in spark-defaults will be "spark". Per-job submission overrides this with `spark-<jobName>` via `--conf` flags, which take precedence over spark-defaults.conf. This gives us always-on telemetry with job-level attribution when using our tooling.

**4. Generic `EMRConfiguration` data class on `EMRClusterConfig`**

Rather than adding spark-specific fields to `EMRClusterConfig`, we add a generic `configurations: List<EMRConfiguration>` that maps to the AWS SDK `Configuration` type. The provisioning service is responsible for building the spark-defaults content.

## Risks / Trade-offs

- **Pyroscope default app name is generic** → All non-CLI-submitted jobs share `pyroscope.application.name=spark`. Acceptable since job-specific names are still set for our submissions.
- **extraJavaOptions in spark-defaults vs per-job are additive** → Per-job `--conf spark.driver.extraJavaOptions` overrides (not appends to) spark-defaults. The per-job override only needs to set `-Dpyroscope.application.name=spark-<jobName>` since the agents are already loaded from spark-defaults. We must ensure per-job does NOT re-specify `-javaagent` flags (would double-load agents).
