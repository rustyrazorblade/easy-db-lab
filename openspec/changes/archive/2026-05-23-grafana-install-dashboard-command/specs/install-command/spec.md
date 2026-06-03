## MODIFIED Requirements

### Requirement: install clickhouse generates start.sh with dashboard registration
The generated `start.sh` for `install clickhouse` SHALL call `easy-db-lab grafana install` for each JSON file found in `${SCRIPT_DIR}/dashboards/`.

#### Scenario: start.sh installs bundled dashboards
- **WHEN** the user runs `start.sh` after `install clickhouse`
- **THEN** `easy-db-lab grafana install` is called once for each `.json` file in the `dashboards/` subdirectory of the workload output directory

#### Scenario: dashboards directory is present after install clickhouse
- **WHEN** `install clickhouse` is run
- **THEN** the output directory contains a `dashboards/` subdirectory with `clickhouse.json` and `clickhouse-logs.json`

## ADDED Requirements

### Requirement: Template dashboards/ subdir is copied verbatim
Template directories MAY contain a `dashboards/` subdirectory with `.json` files. These files SHALL be copied verbatim (no template substitution) to the output workload directory alongside other rendered files.

#### Scenario: Custom template with dashboards
- **WHEN** `install --from ./my-workload/` is run and `./my-workload/dashboards/` contains JSON files
- **THEN** those JSON files appear in `./<workload>/dashboards/` in the output directory
