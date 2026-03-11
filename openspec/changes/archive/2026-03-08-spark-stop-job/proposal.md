## Why

Running Spark jobs on EMR can take a long time (hours for large bulk writes). There is currently no way to cancel a running or pending job from the CLI — the only option is to terminate the entire EMR cluster (`spark down`), which is destructive and wasteful. AWS EMR provides a `CancelSteps` API that allows cancelling individual steps without affecting the cluster.

## What Changes

- Add a `spark stop` command that cancels a running or pending EMR step by step ID
- Add `cancelJob()` to the `SparkService` interface and implement it in `EMRSparkService` using the AWS `CancelSteps` API
- If no step ID is provided, cancel the most recent step (matching the pattern used by `spark status`)
- Add corresponding domain events to `Event.Emr` for user feedback
- Update user documentation for the `spark` command group

## Capabilities

### New Capabilities
- `spark-job-cancellation`: Ability to cancel running or pending Spark jobs on EMR without terminating the cluster

### Modified Capabilities
- `spark-emr`: Add cancellation as a supported job lifecycle operation

## Impact

- **Commands**: New `SparkStop` command in `commands/spark/`
- **Services**: New method on `SparkService` interface and `EMRSparkService` implementation
- **Events**: New event types in `Event.Emr` for cancellation feedback
- **AWS API**: Uses `EmrClient.cancelSteps()` — no new dependencies needed (already in the EMR SDK)
- **Docs**: Update `docs/` spark command reference
