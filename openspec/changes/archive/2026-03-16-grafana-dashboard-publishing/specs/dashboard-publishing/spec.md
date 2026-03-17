## ADDED Requirements

### Requirement: Dashboard files are top-level project artifacts

Dashboard JSON files SHALL live in a top-level `dashboards/` directory at the project root, not inside `src/main/resources/`.

#### Scenario: Dashboard files exist at project root

- **WHEN** a developer looks at the project structure
- **THEN** all Grafana dashboard JSON files SHALL be located in the `dashboards/` directory

#### Scenario: Dashboards are included in the JAR classpath

- **WHEN** the project is built with Gradle
- **THEN** all files in `dashboards/` SHALL be available as classpath resources in the final artifact

### Requirement: Dashboards are published as a standalone zip artifact

A GitHub Actions workflow SHALL publish all dashboard JSON files as a zip archive on every push to the main branch.

#### Scenario: Zip published on main branch push

- **WHEN** code is pushed to the `main` branch
- **THEN** a GitHub Actions workflow SHALL create a zip archive of the `dashboards/` directory
- **AND** upload it as a release asset under the `latest` tag

#### Scenario: Zip is updated on subsequent pushes

- **WHEN** a new push to `main` occurs and a `latest` release already exists
- **THEN** the workflow SHALL overwrite the existing dashboards zip asset

#### Scenario: Zip contains only dashboard JSON files

- **WHEN** the dashboards zip is downloaded and extracted
- **THEN** it SHALL contain only the JSON files from the `dashboards/` directory with no extra directory nesting
