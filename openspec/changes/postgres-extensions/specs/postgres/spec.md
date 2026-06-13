# PostgreSQL (delta)

## MODIFIED Requirements

### Requirement: REQ-PG-001: K8s-Based Deployment via CloudNativePG

The system MUST deploy PostgreSQL clusters on K8s db nodes via the CloudNativePG (CNPG) operator. The operator SHALL be installed via Helm (`cloudnative-pg/cloudnative-pg` chart into the `cnpg-system` namespace). The PostgreSQL cluster SHALL be defined as a CNPG `Cluster` custom resource named `postgres`. The container image, shared_preload_libraries, and postInitSQL MAY be configured via extension flags — see the `postgres-extensions` spec.

#### Scenario: Default deployment uses standard image
- **GIVEN** a running cluster with K3s
- **WHEN** the user runs `kit install postgres` and `postgres start` with no extension flags
- **THEN** the CNPG operator is installed and a PostgreSQL Cluster CR is deployed using the default `ghcr.io/cloudnative-pg/postgresql` image with no shared_preload_libraries and no postInitSQL

#### Scenario: Install succeeds when CNPG is already installed
- **WHEN** the user runs `kit install postgres` on a cluster where CNPG is already installed
- **THEN** the install succeeds without error
