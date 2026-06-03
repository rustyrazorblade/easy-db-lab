## ADDED Requirements

### Requirement: Status subcommand synthesized for every installed workload
Every installed workload directory SHALL have a `status` subcommand registered automatically by `WorkloadRunnerCommandFactory`, regardless of whether `install.yaml` exists or contains typed phases.

#### Scenario: Workload with install.yaml gets status
- **WHEN** a workload directory exists with `install.yaml`
- **THEN** `easy-db-lab <workload> status` is available as a subcommand

#### Scenario: Workload with bin/-only scripts gets status
- **WHEN** a workload directory exists with only a `bin/` directory and no `install.yaml`
- **THEN** `easy-db-lab <workload> status` is available as a subcommand

### Requirement: Status command shows running state
The status command SHALL check whether the workload is running in K8s and display the result.

#### Scenario: Helm-installed workload is running
- **WHEN** `runtime.type: helm` is declared and the release is DEPLOYED
- **THEN** output shows `Running` with ready pod count (e.g. `Running (2/2 pods ready)`)

#### Scenario: Helm-installed workload is not running
- **WHEN** `runtime.type: helm` is declared and the release does not exist
- **THEN** output shows `Stopped`

#### Scenario: Running state detected from typed start phase
- **WHEN** `install.yaml` has a typed `start` phase with `type: helm` and no `runtime:` block
- **THEN** running state is inferred from the helm release defined in the start phase

#### Scenario: Running state falls back to pod label selector
- **WHEN** no `runtime:` block and no typed phases exist
- **THEN** running state is checked via `kubectl get pods -l app.kubernetes.io/name=<workload> -n default`

#### Scenario: Cluster state unavailable
- **WHEN** `cluster-state.yaml` does not exist
- **THEN** status command prints an error and exits with code 1

### Requirement: Status command shows connection endpoints
The status command SHALL display connection endpoints declared in `install.yaml` with private IPs resolved from cluster state.

#### Scenario: HTTP endpoint displayed
- **WHEN** an endpoint with `type: http` is declared
- **THEN** output shows `<name>   http   http://<private-ip>:<port>`

#### Scenario: JDBC endpoint displayed
- **WHEN** an endpoint with `type: jdbc` and `scheme: <scheme>` is declared
- **THEN** output shows `<name>   jdbc   jdbc:<scheme>://<private-ip>:<port>/<path>`

#### Scenario: Native endpoint displayed
- **WHEN** an endpoint with `type: native` or `type: cql` is declared
- **THEN** output shows `<name>   native   <private-ip>:<port>`

#### Scenario: Multiple nodes produce multiple URLs
- **WHEN** an endpoint targets `node-type: app` and there are two app nodes
- **THEN** one URL per node is printed

#### Scenario: No endpoints declared
- **WHEN** `install.yaml` has no `endpoints:` section
- **THEN** status output shows running state only, with no endpoints section

### Requirement: APP_NODE_IPS available as template variable
`TemplateVariables` SHALL include `APP_NODE_IPS` as a comma-separated list of private IPs for `ServerType.Stress` nodes.

#### Scenario: App node IPs resolved
- **WHEN** the cluster has two app nodes with private IPs `10.0.1.1` and `10.0.1.2`
- **THEN** `APP_NODE_IPS` resolves to `10.0.1.1,10.0.1.2`

#### Scenario: No app nodes
- **WHEN** the cluster has no app nodes
- **THEN** `APP_NODE_IPS` resolves to an empty string
