## MODIFIED Requirements

### Requirement: Step types cover common K8s workload installation patterns
The CLI SHALL support the following step types:

| Type | Purpose |
|---|---|
| `helm-repo` | Add/update a Helm chart repository |
| `helm` | Install or upgrade a Helm release |
| `helm-uninstall` | Remove a Helm release |
| `namespace` | Create a Kubernetes namespace (idempotent) |
| `manifest` | Render a `.template` file and apply via K8s API |
| `manifest-url` | Apply a manifest from a remote URL |
| `kustomize` | Apply a kustomize overlay via `kubectl apply -k` |
| `wait` | Wait for a K8s resource to reach a condition |
| `delete` | Delete a K8s resource |
| `platform-pvs` | Create local PersistentVolumes via the platform service |
| `configmap` | Create or update a ConfigMap |
| `label` | Add labels to cluster nodes |
| `exec` | Run a command inside a running pod |
| `shell` | Execute a shell command (escape hatch) |

#### Scenario: Helm step installs a chart
- **WHEN** a step with `type: helm` is executed
- **THEN** the CLI adds the repo (if not present), then runs `helm upgrade --install` on the control node via SSH

#### Scenario: Wait step polls until condition is met or times out
- **WHEN** a step with `type: wait` specifies `condition: Ready` and `timeout: 300s`
- **THEN** the CLI polls the named K8s resource until the condition is met or the timeout elapses
- **AND** timeout is reported as a failure with the last observed status

#### Scenario: Delete step is idempotent
- **WHEN** a step with `type: delete` targets a resource that does not exist
- **THEN** the step succeeds (equivalent to `--ignore-not-found`)

#### Scenario: Shell step executes as escape hatch
- **WHEN** a step with `type: shell` is declared
- **THEN** the specified command is executed in a subshell with cluster state variables injected as environment variables
