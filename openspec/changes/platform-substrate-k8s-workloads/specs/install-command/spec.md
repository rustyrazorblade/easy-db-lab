## ADDED Requirements

### Requirement: install command scaffolds workload artifacts

The system SHALL provide an `install` parent command with per-workload subcommands. Each invocation renders template files using `TemplateService` and writes scaffold artifacts (`start.sh`, `stop.sh`, `values.yaml`, `README.md`) to `./<workload>/` in the current directory. The command SHALL NOT modify `ClusterState` or deploy anything to K8s.

#### Scenario: Built-in workload scaffold written

- **WHEN** the user runs `easy-db-lab install clickhouse --replicas 3 --s3-cache 200Gi`
- **THEN** the system SHALL write rendered artifacts to `./clickhouse/start.sh`, `./clickhouse/stop.sh`, `./clickhouse/values.yaml`, and `./clickhouse/README.md`
- **AND** the artifacts SHALL contain cluster-specific values (bucket name, region, node counts) substituted from cluster state
- **AND** `ClusterState` SHALL NOT be modified

#### Scenario: Generated start.sh calls easy-db-lab CLI

- **WHEN** the user examines the generated `start.sh`
- **THEN** it SHALL call `easy-db-lab platform create-pvs` (for stateful workloads) before invoking helm or kubectl
- **AND** it SHALL call only `helm`, `kubectl`, and `easy-db-lab` — no other subprocesses

#### Scenario: Generated stop.sh does not wipe disk

- **WHEN** the user runs the generated `stop.sh`
- **THEN** K8s resources SHALL be removed (helm uninstall, kubectl delete)
- **AND** disk data at `/mnt/db1/<workload>` SHALL be preserved

### Requirement: Profile-directory templates are auto-discovered

The system SHALL scan `~/.easy-db-lab/profiles/<profile>/install/` at install time and treat any subdirectory there as an available workload template. These templates SHALL appear in `install --list` and SHALL be invocable as `install <name>` without requiring `--from`. If a profile-directory template and a built-in template share the same name, the profile-directory template SHALL take precedence.

#### Scenario: Profile template listed alongside built-ins

- **WHEN** the user places a template directory at `~/.easy-db-lab/profiles/default/install/my-db/` and runs `easy-db-lab install --list`
- **THEN** `my-db` SHALL appear in the list alongside built-in workloads (e.g., `clickhouse`, `presto`)

#### Scenario: Profile template installed without --from

- **WHEN** the user runs `easy-db-lab install my-db` and `my-db` exists in the profile install directory
- **THEN** the system SHALL render and scaffold that template exactly as it would a built-in template

#### Scenario: Profile template overrides built-in of same name

- **WHEN** a template named `clickhouse` exists in the profile install directory
- **THEN** `easy-db-lab install clickhouse` SHALL use the profile-directory template, not the built-in

#### Scenario: Empty profile install directory

- **WHEN** `~/.easy-db-lab/profiles/<profile>/install/` does not exist or is empty
- **THEN** `install --list` SHALL show only built-in templates with no error

### Requirement: install --from supports ad-hoc custom template directories

The system SHALL allow `install --from <path> --workload <name>` to scaffold artifacts from a user-supplied template directory. The same template variable contract available to built-in templates SHALL be available to custom templates. Ad-hoc templates SHALL NOT appear in `install --list`.

#### Scenario: Ad-hoc template rendered and written

- **WHEN** the user runs `easy-db-lab install --from ./my-private-db/ --workload my-db --size 500Gi`
- **THEN** the system SHALL render each `.template` file in `./my-private-db/` using `TemplateService` with the standard template variable set
- **AND** SHALL write rendered files to `./my-db/`
- **AND** `my-private-db` SHALL NOT appear in `easy-db-lab install --list`

#### Scenario: Unknown template variables pass through

- **WHEN** a custom template contains a variable not in the standard contract (e.g., `__CUSTOM_VAR__`)
- **THEN** the system SHALL emit a warning listing unresolved variables
- **AND** SHALL write the rendered files with the unresolved placeholder intact (not fail)

### Requirement: install clickhouse detects existing Altinity operator deployment

The system SHALL query K8s to detect an existing `ClickHouseInstallation` CR before scaffolding. It SHALL warn the user if one exists.

#### Scenario: No existing deployment

- **WHEN** the user runs `easy-db-lab install clickhouse` and no `ClickHouseInstallation` CR exists in the cluster
- **THEN** the system SHALL scaffold artifacts normally

#### Scenario: Existing deployment detected

- **WHEN** the user runs `easy-db-lab install clickhouse` and a `ClickHouseInstallation` CR already exists
- **THEN** the system SHALL emit a warning that ClickHouse appears to already be deployed
- **AND** SHALL require `--force` to overwrite the scaffold artifacts

### Requirement: Template variable contract

The system SHALL substitute the following variables in all templates (built-in and custom):

| Variable | Source |
|---|---|
| `__BUCKET_NAME__` | ClusterState AWS bucket |
| `__REGION__` | ClusterState AWS region |
| `__DB_NODE_COUNT__` | Count of db nodes in ClusterState |
| `__APP_NODE_COUNT__` | Count of app nodes in ClusterState |
| `__CONTROL_HOST__` | Control node hostname/IP |
| `__STORAGE_CLASS_WFC__` | Constant: `local-storage-wfc` |
| `__WORKLOAD_NAME__` | `--workload` flag value |
| `__STORAGE_SIZE__` | `--size` flag value |
| `__KUBECONFIG__` | Path to kubeconfig |

Built-in subcommands may inject additional workload-specific variables (e.g., `__REPLICAS__`, `__S3_CACHE_SIZE__`).

#### Scenario: Standard variables substituted

- **WHEN** `install` renders any template
- **THEN** all variables from the standard contract SHALL be substituted with cluster-specific values

#### Scenario: Workload-specific variables substituted

- **WHEN** `install clickhouse --replicas 3 --s3-cache 200Gi` renders templates
- **THEN** `__REPLICAS__` SHALL be substituted with `3` and `__S3_CACHE_SIZE__` with `200Gi`

### Requirement: status command shows workload pod health

The system SHALL include a workloads section in `easy-db-lab status` output that queries K8s directly for pod placement and readiness. It SHALL NOT read workload state from `ClusterState`.

#### Scenario: Running workload pods shown

- **WHEN** the user runs `easy-db-lab status` and ClickHouse pods are running in K8s
- **THEN** the status output SHALL include a workloads section listing pod names, nodes, and readiness
- **AND** this information SHALL come from a live K8s query, not from `ClusterState`

#### Scenario: No workloads deployed

- **WHEN** the user runs `easy-db-lab status` and no workload pods are running
- **THEN** the status output SHALL show an empty or absent workloads section (not an error)
