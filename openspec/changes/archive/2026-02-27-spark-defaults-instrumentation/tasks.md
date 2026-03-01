## 1. EMR Configuration Support

- [x] 1.1 Add `EMRConfiguration` data class and `configurations` field to `EMRClusterConfig` in `EMRTypes.kt`
- [x] 1.2 Apply configurations to `RunJobFlowRequest` in `EMRService.createCluster()` — convert `EMRConfiguration` to AWS SDK `Configuration` objects

## 2. Spark-Defaults Classification

- [x] 2.1 Build spark-defaults classification in `EMRProvisioningService.provisionEmrCluster()` with OTel/Pyroscope agent flags (`spark.driver.extraJavaOptions`, `spark.executor.extraJavaOptions`) and OTEL env vars (`spark.driverEnv.*`, `spark.executorEnv.*`, `spark.yarn.appMasterEnv.*`), using control node IP from cluster state
- [x] 2.2 Pass the configurations to `EMRClusterConfig` alongside existing bootstrap actions

## 3. Simplify Per-Job Enrichment

- [x] 3.1 Simplify `EMRSparkService.buildOtelSparkConf()` to only set `-Dpyroscope.application.name=spark-<jobName>` in extraJavaOptions (remove agent flags)
- [x] 3.2 Simplify `EMRSparkService.buildOtelEnvVars()` to only set `OTEL_SERVICE_NAME=spark-<jobName>` (remove exporter config)

## 4. Tests

- [x] 4.1 Add/update `EMRProvisioningServiceTest` to verify spark-defaults configurations are built and passed to `EMRClusterConfig`
- [x] 4.2 Update `EMRSparkServiceTest` to verify simplified per-job enrichment (only name overrides, no agent flags)
- [x] 4.3 Run `./gradlew ktlintCheck` and `./gradlew detekt` — fix any issues

## 5. Documentation

- [x] 5.1 Update `CLAUDE.md` observability section to document spark-defaults classification for agent activation
