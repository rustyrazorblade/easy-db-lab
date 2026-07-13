# Kit Lifecycle Hooks

## Purpose

Let a kit declare hook scripts in its `config.yaml` that run after any other kit starts or stops, so observer kits can react to peer kit lifecycle events with retry-on-failure semantics.

## Requirements

### Requirement: config.yaml declares post-kit hooks
A kit's `config.yaml` MAY declare hook scripts that execute after any other kit starts or stops. Hooks SHALL be declared under a `hooks` key with `post-workload-start` and/or `post-workload-stop` sub-keys, each specifying a `script` path relative to the kit directory. An optional `kits` list scopes the hook to specific triggering kit names; when absent the hook fires for all kits.

#### Scenario: Hook fires after any kit starts
- **WHEN** kit A declares `hooks.post-workload-start.script` with no `kits` filter
- **AND** kit B successfully starts
- **THEN** kit A's hook script is executed

#### Scenario: Hook fires only for scoped kits
- **WHEN** kit A declares `hooks.post-workload-start` with `kits: [cassandra]`
- **AND** kit B (not cassandra) starts
- **THEN** kit A's hook script is NOT executed

#### Scenario: Hook does not fire for own start
- **WHEN** kit A declares `hooks.post-workload-start`
- **AND** kit A itself starts
- **THEN** kit A's hook script is NOT executed for its own start

#### Scenario: Hook does not fire if declaring kit is not installed
- **WHEN** kit A is not installed in the working directory
- **AND** kit B starts
- **THEN** no hook from kit A is executed

### Requirement: Hook scripts are retried on failure
If a hook script exits with a non-zero status, the system SHALL retry using resilience4j with exponential backoff (default: 3 attempts). If all attempts fail, an error event SHALL be emitted to stderr and execution continues. The cluster is left as-is; no rollback is performed.

#### Scenario: Transient hook failure recovers on retry
- **WHEN** a hook script fails on the first attempt but succeeds on the second
- **THEN** no error is emitted and the overall start command succeeds

#### Scenario: Exhausted retries emit error
- **WHEN** a hook script fails on all retry attempts
- **THEN** an `Event.Kit.HookFailed` error event is emitted to stderr
- **AND** the triggering kit's start command still exits successfully

### Requirement: Hooks fire on EC2/SystemD service lifecycle
The hook system SHALL apply to `cassandra start`, `cassandra stop`, and equivalent commands for other EC2-based services, not only to K8s kit commands.

#### Scenario: Cassandra start fires hooks in installed K8s kits
- **WHEN** `easy-db-lab cassandra start` completes successfully
- **AND** presto is installed with a `post-workload-start` hook
- **THEN** presto's hook script is executed
