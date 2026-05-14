## MODIFIED Requirements

### Requirement: End-to-end test runner

The system SHALL provide a bash test runner (`bin/end-to-end-test`) that provisions real AWS infrastructure, deploys services, runs validation steps, and tears down the environment. All steps run regardless of earlier failures; diagnostics are captured for each failure.

The runner SHALL source `$PROJECT_ROOT/.env` at startup if the file exists. The `AWS_PROFILE` used for all AWS operations SHALL come from the environment or `.env`; it SHALL NOT be hardcoded in the script. When `--stop-on-failure` is passed, the runner SHALL halt immediately after the first failing step instead of continuing.

#### Scenario: Resilient step execution
- **WHEN** a test step fails and `--stop-on-failure` is not set
- **THEN** the failure is logged with exit code, step output, pod status, K8s events, and disk usage
- **AND** execution continues to the next step

#### Scenario: Stop on first failure
- **WHEN** a test step fails and `--stop-on-failure` is set
- **THEN** the runner halts immediately after recording the failure
- **AND** the cluster remains running for inspection (teardown behavior follows `--no-teardown` semantics)

#### Scenario: Teardown behavior on failure
- **WHEN** any step has failed during the run
- **THEN** the full failure log is printed at the end
- **AND** the script prompts for confirmation before tearing down (keeping the environment alive for debugging)

#### Scenario: Clean exit on success
- **WHEN** all steps pass
- **THEN** the environment is torn down automatically and the script exits with code 0

#### Scenario: AWS profile from .env
- **WHEN** a developer sets `AWS_PROFILE=my-profile` in `.env` and runs `bin/end-to-end-test`
- **THEN** all AWS CLI and SDK calls use `my-profile`
- **AND** no `AWS_PROFILE` value is hardcoded in the script itself
