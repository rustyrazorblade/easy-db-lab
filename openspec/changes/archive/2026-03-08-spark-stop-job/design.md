## Context

The `spark` command group currently supports job submission (`submit`), monitoring (`status`, `jobs`), log viewing (`logs`), cluster init/teardown (`init`, `down`), but has no way to cancel individual jobs. Users must terminate the entire EMR cluster to stop a running job.

AWS EMR provides the `CancelSteps` API (available since EMR 5.28.0) which can cancel PENDING or RUNNING steps without affecting the cluster.

## Goals / Non-Goals

**Goals:**
- Allow users to cancel a specific Spark job by step ID
- Default to cancelling the most recent job (matching `spark status` pattern)
- Provide clear feedback on cancellation result

**Non-Goals:**
- Bulk cancellation of multiple jobs at once
- Automatic cancellation on CLI interrupt/SIGINT
- Waiting for the cancellation to fully complete (the API is async)

## Decisions

### 1. Command name: `spark stop`

Use `spark stop` rather than `spark cancel` or `spark kill`. The codebase already uses "stop" for lifecycle operations (e.g., `cassandra stop`, `stress stop`), and it aligns with the existing naming convention.

**Alternative**: `spark cancel` — more technically accurate to the EMR API name, but inconsistent with the rest of the CLI.

### 2. Cancellation strategy: TERMINATE_PROCESS

Use `TERMINATE_PROCESS` (SIGKILL) rather than `SEND_INTERRUPT` (SIGTERM). Spark jobs that are bulk-writing data are unlikely to have graceful shutdown handlers, so a SIGTERM would just add delay before eventually being killed anyway.

**Alternative**: `SEND_INTERRUPT` — more graceful, but in practice Spark Cassandra Connector and cassandra-analytics writers don't implement graceful shutdown, so this would just add a timeout delay.

### 3. Service interface: add `cancelJob()` to `SparkService`

Add a single method `cancelJob(clusterId: String, stepId: String): Result<CancelJobResult>` to the existing `SparkService` interface. The result includes the step ID and the cancel status returned by EMR (e.g., `SUBMITTED`, `FAILED`).

### 4. Default to most recent job

Follow the same pattern as `SparkStatus`: if no `--step-id` is provided, cancel the most recent job. This is convenient for the common case of "I just submitted a job and want to stop it."

## Risks / Trade-offs

- **[Async cancellation]** → The EMR API is async — the step may not be cancelled immediately. The command will report the API response status, not the final step state. Users can check with `spark status` afterward.
- **[Already-completed jobs]** → Cancelling a completed/failed job returns a non-error status from EMR (it just reports it can't be cancelled). We'll relay this information to the user.
