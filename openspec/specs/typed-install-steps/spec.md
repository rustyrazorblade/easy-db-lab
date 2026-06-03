## ADDED Requirements

### Requirement: kit.yaml declares lifecycle phases as typed step sequences
`kit.yaml` SHALL support four optional top-level lifecycle keys (`install`, `start`, `stop`,
`uninstall`), each containing an ordered list of typed steps. Each step SHALL have a `type`
discriminator field that determines its behavior and required fields.

#### Scenario: Lifecycle phases parsed from kit.yaml
- **WHEN** `kit.yaml` contains an `install` key with a list of typed steps
- **THEN** the CLI parses each step into the corresponding `InstallStep` subtype
- **AND** unknown `type` values cause a parse error (fail fast)

#### Scenario: Lifecycle phases are optional
- **WHEN** `kit.yaml` omits the `stop` phase
- **THEN** `easy-db-lab <kit> stop` falls back to the `bin/stop.sh` script if present
- **AND** if neither a `stop` phase nor a `bin/stop.sh` exists, the command fails with a clear error

### Requirement: Step types cover common K8s kit installation patterns
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

### Requirement: Step fields support `${VAR}` interpolation from cluster state
String fields in step definitions SHALL support `${VAR}` substitution using the same variable map
as `TemplateVariables.toMap()` (e.g., `${CLUSTER_NAME}`, `${CONTROL_NODE_IP}`, `${DB_NODE_COUNT}`).

#### Scenario: Cluster name interpolated in Wait step name field
- **WHEN** a `wait` step declares `name: "${CLUSTER_NAME}-scylladb"`
- **THEN** the CLI substitutes the actual cluster name before issuing the K8s watch
- **AND** the literal string is used if cluster state is unavailable (no substitution error)

#### Scenario: Unresolved variable emits warning
- **WHEN** a step field contains `${UNKNOWN_VAR}` with no matching cluster state key
- **THEN** the CLI emits a warning event and uses the literal string

### Requirement: kit.yaml declares kit version and dashboard files
`kit.yaml` SHALL support:
- A top-level `version` field (string) identifying the kit version being installed
- A top-level `dashboards` list of objects with `path` and optional `name` fields

Dashboards SHALL be installed into Grafana automatically after the `start` phase completes
successfully, replacing the hardcoded directory scan in `KitRunnerCommand`.

#### Scenario: Dashboards installed after successful start
- **WHEN** `kit.yaml` declares a `dashboards` list and `easy-db-lab <kit> start` exits
  successfully
- **THEN** each listed dashboard JSON file is installed into Grafana via `GrafanaDashboardService`

#### Scenario: Dashboards skipped after failed start
- **WHEN** any step in the `start` phase fails
- **THEN** dashboard installation is skipped and the failure is reported

### Requirement: Collision detection is configurable per phase
`collisionCheck` in `kit.yaml` SHALL accept either a boolean (applies to `start` only,
preserving backward compatibility) or a map of phase names to booleans.

#### Scenario: Top-level boolean applies to start phase only
- **WHEN** `collisionCheck: true` is set at the top level
- **THEN** the CLI checks for an existing K8s resource before executing the `start` phase
- **AND** the `install` phase is not guarded (idempotent by design)

#### Scenario: Per-phase collision check
- **WHEN** `collisionCheck: {start: true, install: false}` is set
- **THEN** the `start` phase checks for collision; the `install` phase does not

### Requirement: Helm step values support nested YAML structures
The `values` field of a `helm` step SHALL accept arbitrary nested YAML (not restricted to flat
`Map<String, String>`). Values SHALL be serialized to a temporary file and passed to helm via
`--values`.

#### Scenario: Nested values passed to helm
- **WHEN** a `helm` step declares `values` with nested maps and mixed types
- **THEN** the CLI writes a temp values.yaml and passes it to `helm upgrade --install --values`

### Requirement: Step execution failures stop the phase and are reported as events
When any step in a phase fails, the CLI SHALL stop executing remaining steps in that phase,
emit a `Event.Kit.StepFailed` event with the step type and error detail, and exit with a
non-zero exit code.

#### Scenario: Step failure aborts remaining steps
- **WHEN** step 2 of 4 in the `install` phase fails
- **THEN** steps 3 and 4 are not executed
- **AND** an error event is emitted identifying the step type, phase, and kit name

### Requirement: Helm operations execute on the control node via SSH
`helm-repo`, `helm`, and `helm-uninstall` steps SHALL execute helm commands on the control node
via `RemoteOperationsService` rather than requiring a local helm binary.

#### Scenario: No local helm required
- **WHEN** a user runs `easy-db-lab install scylladb` with helm steps
- **THEN** the CLI connects to the control node and runs helm there
- **AND** the local machine does not need helm installed
