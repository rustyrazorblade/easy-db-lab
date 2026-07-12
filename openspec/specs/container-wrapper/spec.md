# Container Wrapper

## Purpose

Provides an executable `easy-db-lab` wrapper script inside the jib-built container image so workload scripts can invoke the CLI directly, and resolves the `EASY_DB_LAB_EXEC` path correctly in both dev and container environments.

## Requirements

### Requirement: Container image includes easy-db-lab executable at /usr/local/bin/easy-db-lab
The jib-built container image SHALL include a shell script at `/usr/local/bin/easy-db-lab` that invokes the application JAR with the correct JVM flags. This script SHALL be executable.

#### Scenario: easy-db-lab callable inside container
- **WHEN** a workload script running inside the container calls `${EASY_DB_LAB_EXEC}`
- **THEN** the CLI executes normally without requiring the dev wrapper or docker-compose

#### Scenario: Container wrapper does not start OTel docker-compose
- **WHEN** easy-db-lab is invoked via the container wrapper
- **THEN** no docker-compose process is started; OTel is configured via `OTEL_EXPORTER_OTLP_ENDPOINT` environment variable

### Requirement: Container entrypoint uses the wrapper script
The jib container entrypoint SHALL be `["/usr/local/bin/easy-db-lab"]` rather than the implicit `java -cp` launcher derived from `mainClass`.

#### Scenario: Container runs CLI via wrapper
- **WHEN** the container is started with `docker run ghcr.io/rustyrazorblade/easy-db-lab:latest --help`
- **THEN** the CLI help output is printed

### Requirement: EASY_DB_LAB_EXEC resolves correctly in both dev and container
The `EASY_DB_LAB_EXEC` variable injected by `WorkloadRunner` SHALL resolve to:
- Dev: `${easydblab.apphome}/bin/easy-db-lab`
- Container: `/usr/local/bin/easy-db-lab`

The distinction is made by checking whether `/usr/local/bin/easy-db-lab` exists; if so, use it, otherwise use the apphome-relative path.

#### Scenario: Dev resolution
- **WHEN** `easydblab.apphome` is `/home/user/easy-db-lab` and `/usr/local/bin/easy-db-lab` does not exist
- **THEN** `EASY_DB_LAB_EXEC=/home/user/easy-db-lab/bin/easy-db-lab`

#### Scenario: Container resolution
- **WHEN** `/usr/local/bin/easy-db-lab` exists
- **THEN** `EASY_DB_LAB_EXEC=/usr/local/bin/easy-db-lab`
