## MODIFIED Requirements

### Requirement: install <workload> subcommands are generated from install.yaml
The `install` command group SHALL dynamically register subcommands for each discoverable template that contains an `install.yaml`. The subcommand's flags, descriptions, and defaults SHALL be derived entirely from `install.yaml`. No Kotlin class is required per workload.

**Replaces**: per-workload Kotlin classes (`InstallClickHouse`, `InstallPresto`).

#### Scenario: install clickhouse runs from install.yaml
- **WHEN** `install clickhouse --size 100Gi` is run
- **THEN** the flags are parsed from `install.yaml`, variables are resolved, and templates are rendered — without any workload-specific Kotlin code

#### Scenario: New workload added without code change
- **WHEN** a new template directory with `install.yaml` is added to the classpath or profile dir
- **THEN** `install <new-workload>` is available as a subcommand on next invocation

## REMOVED Requirements

### Requirement: InstallClickHouse Kotlin command class
**Reason**: Replaced by `install.yaml` in the clickhouse template directory and generic dynamic subcommand generation.
**Migration**: `install clickhouse` continues to work identically; flags and behavior are preserved via `install.yaml`.

### Requirement: InstallPresto Kotlin command class
**Reason**: Replaced by `install.yaml` in the presto template directory and generic dynamic subcommand generation.
**Migration**: `install presto` continues to work identically; flags and behavior are preserved via `install.yaml`.
