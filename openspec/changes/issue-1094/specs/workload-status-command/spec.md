## MODIFIED Requirements

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
- **THEN** running state is checked via `kubectl get pods -l app.kubernetes.io/name=<workload> -n <namespace>` in the cluster namespace

#### Scenario: Cluster state unavailable
- **WHEN** `cluster-state.yaml` does not exist
- **THEN** status command prints an error and exits with code 1

