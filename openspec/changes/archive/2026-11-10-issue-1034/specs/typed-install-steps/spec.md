## MODIFIED Requirements

### Requirement: Helm operations execute on the control node via SSH
`helm-repo`, `helm`, and `helm-uninstall` steps SHALL execute helm through `ClusterTooling`.  On AWS the SSH form runs helm on the control node, so the local machine does not need helm installed.  With the local form bound, helm runs as a local process and must be installed on the host, as it already must be for kit shell steps.

#### Scenario: No local helm required
- **WHEN** a user runs `easy-db-lab install scylladb` with helm steps and the SSH form is bound
- **THEN** the CLI connects to the control node and runs helm there
- **AND** the local machine does not need helm installed

#### Scenario: Local form runs helm on the host
- **WHEN** a kit with helm steps is installed with the local form bound
- **THEN** helm runs on the host in the kit process environment
- **AND** no SSH connection is attempted
