## 1. Service Layer

- [x] 1.1 Add `CancelJobResult` data class to `SparkService` (stepId, status, reason)
- [x] 1.2 Add `cancelJob(clusterId: String, stepId: String): Result<CancelJobResult>` to `SparkService` interface
- [x] 1.3 Implement `cancelJob()` in `EMRSparkService` using `EmrClient.cancelSteps()` with `TERMINATE_PROCESS` strategy and resilience4j retry

## 2. Events

- [x] 2.1 Add `Event.Emr.JobCancelling(stepId)` event
- [x] 2.2 Add `Event.Emr.JobCancelled(stepId, status, reason)` event

## 3. Command

- [x] 3.1 Create `SparkStop` command with `--step-id` option (defaults to most recent job, following `SparkStatus` pattern)
- [x] 3.2 Register `SparkStop` in `Spark.kt` parent command subcommands list
- [x] 3.3 Update `Spark.kt` KDoc to include `stop` in the available sub-commands list

## 4. Tests

- [x] 4.1 Unit test `SparkStop` command (validates cluster, resolves most recent step when no step-id provided, calls cancelJob)
- [x] 4.2 Unit test `EMRSparkService.cancelJob()` (correct API call construction, result mapping)

## 5. Documentation

- [x] 5.1 Update spark command documentation in `docs/` to include `spark stop`
