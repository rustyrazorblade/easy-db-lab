## ADDED Requirements

### Requirement: install.yaml declares post-workload hooks
A workload's `install.yaml` MAY declare hook scripts that execute after any other workload starts or stops. Hooks are declared under a `hooks` key with `post-workload-start` and/or `post-workload-stop` sub-keys, each specifying a `script` path relative to the workload directory. An optional `workloads` list scopes the hook to specific triggering workload names; when absent the hook fires for all workloads.

#### Scenario: Hook fires after any workload starts
- **WHEN** workload A declares `hooks.post-workload-start.script` with no `workloads` filter
- **AND** workload B successfully starts
- **THEN** workload A's hook script is executed

#### Scenario: Hook fires only for scoped workloads
- **WHEN** workload A declares `hooks.post-workload-start` with `workloads: [cassandra]`
- **AND** workload B (not cassandra) starts
- **THEN** workload A's hook script is NOT executed

#### Scenario: Hook does not fire for own start
- **WHEN** workload A declares `hooks.post-workload-start`
- **AND** workload A itself starts
- **THEN** workload A's hook script is NOT executed for its own start

#### Scenario: Hook does not fire if declaring workload is not installed
- **WHEN** workload A is not installed in the working directory
- **AND** workload B starts
- **THEN** no hook from workload A is executed

### Requirement: Hook scripts are retried on failure
If a hook script exits with a non-zero status, the system SHALL retry using resilience4j with exponential backoff (default: 3 attempts). If all attempts fail, an error event SHALL be emitted to stderr and execution continues. The cluster is left as-is; no rollback is performed.

#### Scenario: Transient hook failure recovers on retry
- **WHEN** a hook script fails on the first attempt but succeeds on the second
- **THEN** no error is emitted and the overall start command succeeds

#### Scenario: Exhausted retries emit error
- **WHEN** a hook script fails on all retry attempts
- **THEN** an `Event.Workload.HookFailed` error event is emitted to stderr
- **AND** the triggering workload's start command still exits successfully

### Requirement: Hooks fire on EC2/SystemD service lifecycle
The hook system SHALL apply to `cassandra start`, `cassandra stop`, and equivalent commands for other EC2-based services, not only to K8s workload commands.

#### Scenario: Cassandra start fires hooks in installed K8s workloads
- **WHEN** `easy-db-lab cassandra start` completes successfully
- **AND** presto is installed with a `post-workload-start` hook
- **THEN** presto's hook script is executed
