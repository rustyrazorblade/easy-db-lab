## 1. K8s Storage and Node Labeling (Platform Layer)

- [x] 1.1 Add `ensureLocalStorageWfcClass` to `DefaultK8sStorageOperations` — StorageClass `local-storage-wfc` with `WaitForFirstConsumer` and `Delete` reclaim, mirroring existing `ensureLocalStorageClass`
- [x] 1.2 Add `ensureLocalStorageWfcClass` to the `K8sStorageOperations` interface
- [x] 1.3 Call `ensureLocalStorageWfcClass` from `Up.kt` alongside the existing `ensureLocalStorageClass` call
- [x] 1.4 Extend `labelDbNodesWithOrdinals` in `Up.kt` to also label app (`ServerType.Stress`) nodes with `easydblab.com/node-ordinal=N`; rename method to `labelNodesWithOrdinals`
- [x] 1.5 Add `LOCAL_STORAGE_WFC_CLASS` constant to `Constants.K8s`
- [x] 1.6 Write tests for `ensureLocalStorageWfcClass` verifying correct binding mode and reclaim policy
- [x] 1.7 Write tests for app node ordinal labeling in the extended method

## 2. platform create-pvs Command

- [x] 2.1 Create `commands/platform/Platform.kt` — PicoCLI parent command group
- [x] 2.2 Create `commands/platform/PlatformCreatePvs.kt` — flags: `--workload`, `--size`, `--node-type` (default: db); calls `K8sService.createLocalPersistentVolumes` with `local-storage-wfc` StorageClass
- [x] 2.3 Register `Platform` command in `Commands.kt`
- [x] 2.4 Add domain events for PV creation via `platform create-pvs` (reuse existing `Event.K8s.LocalPvsCreated` or add a wrapper event)
- [x] 2.5 Write tests for `PlatformCreatePvs` verifying PV creation with correct StorageClass and node affinity

## 3. platform info Command

- [x] 3.1 Create `commands/platform/PlatformInfo.kt` — queries K8s for StorageClasses, lists PV counts per node pool, displays node selector labels and ordinal key, emits example helm values snippet
- [x] 3.2 Add domain events for platform info output
- [x] 3.3 Write tests for `PlatformInfo` output formatting

## 4. Template System

- [x] 4.1 Create `TemplateVariables` data class collecting all standard contract variables from `ClusterState` plus workload-specific flags (`__BUCKET_NAME__`, `__REGION__`, `__DB_NODE_COUNT__`, `__APP_NODE_COUNT__`, `__CONTROL_HOST__`, `__STORAGE_CLASS_WFC__`, `__WORKLOAD_NAME__`, `__STORAGE_SIZE__`, `__KUBECONFIG__`)
- [x] 4.2 Add a `renderWorkloadTemplate(templateContent: String, vars: TemplateVariables): String` method (or extend `TemplateService`) that substitutes all standard variables and emits warnings for unresolved `__VAR__` placeholders
- [x] 4.3 Write tests using the real `TemplateService` (never mock it) verifying variable substitution and unresolved-variable warnings

## 5. install Command Group and Template Discovery

- [x] 5.1 Create `InstallTemplateResolver` service that resolves templates from three sources in priority order: (1) profile directory `~/.easy-db-lab/profiles/<profile>/install/<name>/`, (2) built-in classpath `install/<name>/`, (3) `--from <path>` ad-hoc. Profile overrides built-in when names collide.
- [x] 5.2 Create `commands/install/Install.kt` — PicoCLI parent command with `--from` and `--workload` options; `--list` flag that prints all discoverable templates (profile + built-in, not --from)
- [x] 5.3 Implement `Install --from` logic: render `.template` files in the supplied directory via `TemplateVariables`, write rendered files to `./<workload>/`
- [x] 5.4 Implement `install <name>` resolution via `InstallTemplateResolver` — profile directory first, then classpath
- [x] 5.5 Register `Install` command in `Commands.kt`
- [x] 5.6 Add domain events for scaffold artifact writing (e.g., `Event.Install.ArtifactWritten(workload, filePath)`) and for template source (built-in vs profile vs ad-hoc)
- [x] 5.7 Write tests for `InstallTemplateResolver`: profile-dir template discovered, profile overrides built-in, empty profile dir handled gracefully, --from path not in list
- [x] 5.8 Write tests for `--from` rendering: all standard variables substituted, unresolved variables emit warning, files written to correct path

## 6. install clickhouse Subcommand

- [x] 6.1 Create `commands/install/InstallClickHouse.kt` — flags mirroring `clickhouse init` (`--replicas`, `--s3-cache`, `--s3-cache-on-write`, `--s3-tier-move-factor`); adds `__REPLICAS__`, `__S3_CACHE_SIZE__`, `__S3_CACHE_ON_WRITE__`, `__S3_TIER_MOVE_FACTOR__` to template variables
- [x] 6.2 Add K8s collision detection: query for existing `ClickHouseInstallation` CR; warn and require `--force` if found
- [x] 6.3 Create template directory `src/main/resources/com/rustyrazorblade/easydblab/install/clickhouse/` with `README.md.template`, `values.yaml.template`, `start.sh.template`, `stop.sh.template`
- [x] 6.4 `start.sh.template`: call `easy-db-lab platform create-pvs --workload clickhouse --size __STORAGE_SIZE__`, then install Altinity operator via helm, then apply ClickHouseInstallation CR, then `kubectl wait`
- [x] 6.5 `stop.sh.template`: helm uninstall Altinity operator + kubectl delete CR (no disk cleanup)
- [x] 6.6 `values.yaml.template` / CR template: ClickHouseInstallation CR targeting `type=db` nodes, using `local-storage-wfc`, referencing `__REPLICAS__` and S3 config variables
- [x] 6.7 Write tests for `InstallClickHouse`: verify rendered artifacts, collision detection path, `--force` override

## 7. install presto Subcommand

- [x] 7.1 Create `commands/install/InstallPresto.kt` — flags: `--workers` (default: app node count); adds `__WORKERS__` to template variables
- [x] 7.2 Create template directory `src/main/resources/com/rustyrazorblade/easydblab/install/presto/` with `README.md.template`, `values.yaml.template`, `start.sh.template`, `stop.sh.template`
- [x] 7.3 `start.sh.template`: helm install Trino chart targeting `type=app` nodes (no `platform create-pvs` — stateless)
- [x] 7.4 `stop.sh.template`: helm uninstall Trino
- [x] 7.5 Write tests for `InstallPresto`: verify worker count defaults to app node count, rendered artifacts target `type=app`

## 8. cleanup Command

- [x] 8.1 Create `commands/Cleanup.kt` — accepts workload name; builds a K8s Job via Fabric8 that runs on each db node and deletes `/mnt/db1/<workload>`
- [x] 8.2 Register `Cleanup` command in `Commands.kt`
- [x] 8.3 Add domain events for cleanup job creation and completion
- [x] 8.4 Write tests for `Cleanup` verifying Job spec (correct node selector, volume path, command)

## 9. status Workloads Section

- [x] 9.1 Add a workloads section to `Status.kt` output that queries K8s for pods not in system namespaces, groups by workload label, shows pod name, node, and readiness
- [x] 9.2 Add domain events for workload pod status display
- [x] 9.3 Write tests for the workload status query (mock K8s client or use TestContainers K8s)

## 10. Container: Bundle helm and kubectl

- [x] 10.1 Research and decide base image approach: custom base image with helm/kubectl pre-installed vs. Jib `extraDirectories`
- [x] 10.2 Update Jib config in `build.gradle.kts` to include helm and kubectl binaries in the container image
- [x] 10.3 Verify container image builds and both binaries are executable at expected paths

## 11. Spec and Documentation Updates

- [x] 11.1 Archive specs from this change into `openspec/specs/platform-substrate/spec.md` and `openspec/specs/install-command/spec.md`
- [x] 11.2 Update `openspec/specs/instance-storage-validation/spec.md` with the clarifying note about workload-scoped PVs being created lazily
- [x] 11.3 Write `docs/platform-substrate.md` — explains the two-layer model, StorageClass, node labels, template variable contract, `platform` commands, and how to add custom templates to the profile directory
- [x] 11.4 Write `docs/install-clickhouse.md` — usage, flags, start/stop workflow, prerequisites
- [x] 11.5 Write `docs/install-presto.md` — usage, flags, start/stop workflow
- [x] 11.6 Update `CLAUDE.md` if any architecture or pattern descriptions are affected
