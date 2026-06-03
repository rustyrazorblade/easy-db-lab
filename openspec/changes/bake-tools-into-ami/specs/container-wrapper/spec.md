## MODIFIED Requirements

### Requirement: Container image includes easy-db-lab executable at /usr/local/bin/easy-db-lab
The jib-built container image SHALL include a shell script at `/usr/local/bin/easy-db-lab` that invokes the application JAR with the correct JVM flags. This script SHALL be executable. The container image SHALL be built directly from `eclipse-temurin:21-jre` — no custom base image or separate base image publishing workflow is used.

#### Scenario: easy-db-lab callable inside container
- **WHEN** a workload script running inside the container calls `${EASY_DB_LAB_EXEC}`
- **THEN** the CLI executes normally without requiring the dev wrapper or docker-compose

#### Scenario: Container wrapper does not start OTel docker-compose
- **WHEN** easy-db-lab is invoked via the container wrapper
- **THEN** no docker-compose process is started; OTel is configured via `OTEL_EXPORTER_OTLP_ENDPOINT` environment variable

#### Scenario: Container does not include helm or kubectl
- **WHEN** the container image is inspected
- **THEN** neither helm nor kubectl is present in the container filesystem

## REMOVED Requirements

### Requirement: Custom base image published to ghcr.io
**Reason:** Helm and kubectl are now baked into the AMI and run on cluster nodes via SSH. The container no longer needs these tools, so the custom base image (`ghcr.io/rustyrazorblade/easy-db-lab-base`) and its publishing workflow serve no purpose.
**Migration:** Remove `docker/Dockerfile` and `.github/workflows/publish-base-image.yml`. Update Jib `from.image` to `eclipse-temurin:21-jre`.
