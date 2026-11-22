## MODIFIED Requirements

### Requirement: Helm operations execute on the control node via SSH
`helm-repo`, `helm`, and `helm-uninstall` steps SHALL execute helm through `ClusterTooling`, which runs it on the control node, so the local machine does not need helm installed.  `name` and `url` of a `helm-repo` step accept `${VAR}` interpolation, like the other helm steps.

#### Scenario: No local helm required
- **WHEN** a user runs `easy-db-lab install scylladb` with helm steps
- **THEN** the CLI connects to the control node and runs helm there
- **AND** the local machine does not need helm installed
