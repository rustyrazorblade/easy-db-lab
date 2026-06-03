## ADDED Requirements

### Requirement: Configurable cassandra-analytics source repo and branch

The build script SHALL read the cassandra-analytics Git repository URL and branch from `spark/cassandra-analytics-source.properties` when that file exists, and fall back to the Apache upstream defaults when it does not.

#### Scenario: Default config file points to Apache upstream

- **WHEN** `spark/cassandra-analytics-source.properties` contains `repo=https://github.com/apache/cassandra-analytics.git` and `branch=trunk`
- **THEN** `bin/build-cassandra-analytics` MUST clone from that URL and check out that branch

#### Scenario: Config file points to a fork

- **WHEN** `spark/cassandra-analytics-source.properties` contains a custom `repo` URL and `branch` value
- **THEN** `bin/build-cassandra-analytics` MUST clone from the custom repo URL and check out the specified branch

#### Scenario: CLI flags override the config file

- **WHEN** `bin/build-cassandra-analytics --repo <url> --branch <branch>` is invoked with explicit flags
- **THEN** the script MUST use the CLI-provided values, ignoring whatever is in the config file

#### Scenario: Resolved source is printed at build time

- **WHEN** `bin/build-cassandra-analytics` starts
- **THEN** it MUST print the resolved repo URL and branch before performing any git operations

#### Scenario: Remote mismatch triggers error

- **WHEN** `.cassandra-analytics/` already exists and its configured remote URL differs from the resolved repo URL
- **THEN** the script MUST exit with a non-zero code and print an error message directing the user to re-run with `--force` to re-clone from the new repo
