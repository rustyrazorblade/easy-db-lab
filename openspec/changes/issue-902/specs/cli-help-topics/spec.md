## ADDED Requirements

### Requirement: The observability topic describes experiments, reports, the local stack and the export

The `observability` help topic SHALL describe, as task-oriented guidance: what an experiment is and how it groups clusters within a tenant; the report upload (`report upload <dir>` zips the directory, stores each upload with a UTC timestamp, and never overwrites an earlier one); the local stack commands (`observability local create <dir>` and `observability local dashboards <dir> [--from <dir>]`); and `observability export <path>` with its three selectors `--tenant`, `--cluster` and `--experiment`, and that an export leaves out profiles.

#### Scenario: help observability covers the new workflows
- **WHEN** a user runs `help observability`
- **THEN** the output describes experiments, the report zip upload as directory zipped, timestamped and never overwritten, the local stack commands, and `observability export` with `--tenant`, `--cluster` and `--experiment`

### Requirement: The provisioning topic describes the experiment option

The `provisioning` help topic SHALL describe `init --experiment <name>`: the name rule `^[a-z0-9][a-z0-9_-]{0,79}$`, the default (the cluster's `<name>-<id>`), and that names are unique within a tenant, so the same name in two tenants is two experiments.  It SHALL also say that cluster names may hold `_`.

#### Scenario: help provisioning covers init --experiment
- **WHEN** a user runs `help provisioning`
- **THEN** the output describes `init --experiment`, the name rule, the default, and that names are unique within a tenant
