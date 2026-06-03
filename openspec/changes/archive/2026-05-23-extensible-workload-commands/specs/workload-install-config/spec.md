## ADDED Requirements

### Requirement: install.yaml declares workload CLI arguments
Each template directory SHALL contain an `install.yaml` file that declares the arguments accepted by `install <workload>`, their types, defaults, variable mappings, and whether collision detection is enabled.

#### Scenario: Valid install.yaml is parsed
- **WHEN** `install clickhouse` is run and `install.yaml` exists in the clickhouse template directory
- **THEN** the CLI accepts only the flags declared in `install.yaml` and rejects undeclared flags

#### Scenario: Required arg missing
- **WHEN** `install clickhouse` is run without a flag marked `required: true`
- **THEN** the command fails with a usage error naming the missing flag

#### Scenario: Default references cluster state variable
- **WHEN** an arg declares `default: "${DB_NODE_COUNT}"` and cluster state is loaded
- **THEN** the arg defaults to the actual db node count from cluster state

#### Scenario: Default shown in help when cluster state unavailable
- **WHEN** `install clickhouse --help` is printed before cluster state is loaded
- **THEN** the help output shows the raw `${DB_NODE_COUNT}` form as the default

### Requirement: install.yaml supports collision detection flag
A template's `install.yaml` MAY declare `collision-check: true`. When present, `install <workload>` SHALL check for an existing Deployment, StatefulSet, or CustomResource named after the workload in the default namespace before writing files. If found, the command SHALL abort unless `--force` is passed.

#### Scenario: Collision detected without --force
- **WHEN** `install.yaml` has `collision-check: true` and a matching K8s resource exists
- **THEN** the command emits `Event.Install.CollisionDetected` and exits without writing files

#### Scenario: Collision bypassed with --force
- **WHEN** `install.yaml` has `collision-check: true` and a matching K8s resource exists and `--force` is passed
- **THEN** files are written normally

#### Scenario: No collision-check declared
- **WHEN** `install.yaml` does not declare `collision-check`
- **THEN** no K8s check is performed; files are always written

### Requirement: Suffix transform for numeric args
An arg in `install.yaml` MAY declare a `suffix` field. The rendered variable value SHALL be the parsed value concatenated with the suffix string.

#### Scenario: Suffix applied to integer arg
- **WHEN** `--s3-cache 50` is passed and the arg has `suffix: Gi`
- **THEN** the template variable `__S3_CACHE_SIZE__` is set to `50Gi`
