## REMOVED Requirements

### Requirement: End-to-end test runner

**Reason**: `bin/end-to-end-test` is deleted. It used the repository root as the cluster workspace, and the repository root can no longer be a workspace because it has a `bin/`.

**Migration**: Run lab test plans with the `/easy-db-lab:plan` and `/easy-db-lab:run` skills, or the `agent-test` skill, each in its own workspace under `clusters/`.

### Requirement: Feature flags for optional services

**Reason**: Part of the deleted `bin/end-to-end-test` runner.

**Migration**: A lab test plan lists the services it exercises.

### Requirement: Breakpoint and resume support

**Reason**: Part of the deleted `bin/end-to-end-test` runner.

**Migration**: `/easy-db-lab:run` confirms each step with the user and can resume a plan.

### Requirement: Infrastructure provisioning steps

**Reason**: Part of the deleted `bin/end-to-end-test` runner.

**Migration**: A lab test plan's first step provisions the cluster (`init ... --up`).

### Requirement: S3 backup verification

**Reason**: Part of the deleted `bin/end-to-end-test` runner.

**Migration**: Add a backup check step to a lab test plan when needed.

### Requirement: Cassandra validation steps

**Reason**: Part of the deleted `bin/end-to-end-test` runner.

**Migration**: Add Cassandra steps to a lab test plan.

### Requirement: Spark/EMR validation steps

**Reason**: Part of the deleted `bin/end-to-end-test` runner.

**Migration**: Add Spark steps to a lab test plan.

### Requirement: ClickHouse validation steps

**Reason**: Part of the deleted `bin/end-to-end-test` runner.

**Migration**: Add ClickHouse steps to a lab test plan.

### Requirement: OpenSearch validation steps

**Reason**: Part of the deleted `bin/end-to-end-test` runner.

**Migration**: Add OpenSearch steps to a lab test plan.

### Requirement: Observability stack validation

**Reason**: Part of the deleted `bin/end-to-end-test` runner.

**Migration**: Add observability checks to a lab test plan.

### Requirement: Error handling with interactive recovery

**Reason**: Part of the deleted `bin/end-to-end-test` runner.

**Migration**: `/easy-db-lab:run` and `agent-test` investigate failures inline and leave the cluster up for debugging.

### Requirement: Step listing

**Reason**: Part of the deleted `bin/end-to-end-test` runner.

**Migration**: A lab test plan is a markdown file that lists its steps.
