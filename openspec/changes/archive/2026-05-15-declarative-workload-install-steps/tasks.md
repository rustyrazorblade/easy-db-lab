## 1. InstallStep Sealed Interface and Schema

- [x] 1.1 Define `sealed interface InstallStep` with all step types as data classes: `HelmRepo`, `Helm`, `HelmUninstall`, `Namespace`, `Manifest`, `ManifestUrl`, `Kustomize`, `Wait`, `Delete`, `PlatformPvs`, `ConfigMap`, `Label`, `Exec`, `Shell`
- [x] 1.2 Add kotlinx.serialization polymorphic config for `InstallStep` using `type` as the discriminator; register all subtypes
- [x] 1.3 Handle `values` in the `Helm` step using kaml's `YamlMap` / `YamlNode` to support arbitrary nested YAML
- [x] 1.4 Extend `WorkloadInstallConfig` with: `version: String?`, `dashboards: List<DashboardRef>`, and `install`, `start`, `stop`, `uninstall` as `List<InstallStep>?`
- [x] 1.5 Update `collisionCheck` to accept either `Boolean` or `Map<String, Boolean>` (per-phase); preserve backward compatibility with plain `true`/`false`
- [x] 1.6 Write unit tests: parse a full `install.yaml` with all lifecycle phases and verify each step type deserializes correctly; verify unknown `type` throws

## 2. WorkloadStepExecutor Service

- [x] 2.1 Create `WorkloadStepExecutor` service (Koin-injectable) with a `execute(phase: List<InstallStep>, state: ClusterState, variables: Map<String, String>): Result<Unit>` method
- [x] 2.2 Implement `${VAR}` interpolation helper: replace `${KEY}` tokens in step string fields using the provided variable map; emit warning event for unresolved vars
- [x] 2.3 Implement `HelmRepo` executor: SSH to control node, run `helm repo add <name> <url> && helm repo update`
- [x] 2.4 Implement `Helm` executor: serialize `values` map to a temp file, SSH to control node, run `helm upgrade --install <release> <chart> --namespace <ns> --values <tempfile>`
- [x] 2.5 Implement `HelmUninstall` executor: SSH to control node, run `helm uninstall <release> -n <namespace>`
- [x] 2.6 Implement `Namespace` executor: call `K8sService.createNamespace()` (idempotent)
- [x] 2.7 Implement `Manifest` executor: render the named template via `TemplateService`, apply via `K8sService.applyYaml()`
- [x] 2.8 Implement `ManifestUrl` executor: download the manifest URL content, apply via `K8sService.applyYaml()`
- [x] 2.9 Implement `Kustomize` executor: SSH to control node, run `kubectl apply -k <url>`
- [x] 2.10 Implement `Wait` executor: call `K8sService.waitForCondition()` (or SSH `kubectl wait --for=condition=<cond> <kind>/<name> --timeout=<t>`)
- [x] 2.11 Implement `Delete` executor: call `K8sService.deleteResource()` with `ignoreNotFound = true`
- [x] 2.12 Implement `PlatformPvs` executor: call the `PlatformCreatePvs` service directly (not via subprocess); pass `nodeType` from the step field
- [x] 2.13 Implement `ConfigMap` executor: call `K8sService.createConfigMap()`
- [x] 2.14 Implement `Label` executor: call `K8sService.labelNode()` for each node matching the step's selector
- [x] 2.15 Implement `Exec` executor: call `K8sPodOperations.execInPod()` with the specified command
- [x] 2.16 Implement `Shell` executor: `ProcessBuilder` with cluster state env vars (reuse existing env injection logic)
- [x] 2.17 Add `Event.Workload.StepStarted(workload, phase, stepType, stepIndex)` and `Event.Workload.StepFailed(workload, phase, stepType, stepIndex, error)` events
- [x] 2.18 Write unit tests for the executor: verify interpolation, verify `Delete` ignores not-found, verify step failure aborts remaining steps, verify dashboard installation after successful start

## 3. WorkloadRunnerCommand Integration

- [x] 3.1 Update `WorkloadRunnerCommand` to load `install.yaml` and check for a typed phase matching the script name (`start`, `stop`, `install`, `uninstall`)
- [x] 3.2 If a typed phase exists, dispatch to `WorkloadStepExecutor` instead of `ProcessBuilder`
- [x] 3.3 If no typed phase exists, fall back to the existing `ProcessBuilder` script execution (backward compatibility)
- [x] 3.4 Move dashboard installation from the hardcoded `if (script == "start")` check to the step executor's post-`start` hook using the `dashboards` list from `install.yaml`
- [x] 3.5 Propagate step executor `Result` to the CLI exit code
- [ ] 3.6 Write unit tests: typed phase takes precedence over script, fallback triggers when no phase declared, dashboards installed from `install.yaml` list after successful start

## 4. Clickhouse Migration

- [ ] 4.1 Add `version`, `dashboards`, `install`, `start`, `stop`, `uninstall` phases to `src/main/resources/.../install/clickhouse/install.yaml`
- [ ] 4.2 `install` phase: `helm-repo` (Altinity), `helm` (altinity-clickhouse-operator into kube-system), `platform-pvs` (nodeType: db)
- [ ] 4.3 `start` phase: `namespace` (clickhouse), `manifest` (clickhouseinstallation.yaml.template), `wait` (ClickHouseInstallation ready)
- [ ] 4.4 `stop` phase: `delete` (ClickHouseInstallation CR)
- [ ] 4.5 `uninstall` phase: `helm-uninstall` (altinity-clickhouse-operator)
- [ ] 4.6 Remove `bin/start.sh.template` and `bin/stop.sh.template` from the clickhouse template directory
- [ ] 4.7 Add the clickhouse dashboard to the `dashboards` list in `install.yaml`

## 5. Presto Migration

- [ ] 5.1 Add `version`, `dashboards`, `install`, `start`, `stop`, `uninstall` phases to `src/main/resources/.../install/presto/install.yaml`
- [ ] 5.2 `install` phase: `helm-repo` (Trino), `helm` (trino chart targeting app nodes)
- [ ] 5.3 `start` phase: `wait` (Deployment ready); no `platform-pvs` (Presto is stateless)
- [ ] 5.4 `stop` phase: `helm-uninstall` (or `delete` Deployment, depending on Trino chart semantics)
- [ ] 5.5 `uninstall` phase: `helm-uninstall`
- [ ] 5.6 Remove `bin/start.sh.template` and `bin/stop.sh.template` from the presto template directory
- [ ] 5.7 Verify the `HelmValues` step or `values` inline is sufficient for Presto's `values.yaml.template`; migrate values inline or add a `HelmValues` step type if needed

## 6. install.yaml List Output Update

- [ ] 6.1 Update the `install --list` output to display the `version` field from `install.yaml` alongside the workload name and description

## 7. generate-workload Claude Skill

- [ ] 7.1 Create `.claude/skills/generate-workload/` with a skill definition file
- [ ] 7.2 Skill research phase: use context7/WebSearch to look up helm chart, CRD schema, readiness conditions, and recommended values for the named workload
- [ ] 7.3 Skill proposal phase: generate a draft `install.yaml` with typed steps; present to user before writing any files
- [ ] 7.4 Skill refinement loop: accept user feedback, update draft, re-present; do not write until explicitly approved
- [ ] 7.5 Skill generation phase: write `install.yaml` and any required `.template` files to the correct classpath location
- [ ] 7.6 Skill outputs a checklist item reminding the user to add a `--<workload>` E2E test step and validate with a live cluster

## 8. Spec and Documentation Updates

- [ ] 8.1 Update `openspec/specs/install-command/spec.md` to reflect the new lifecycle phase fields, `version`, `dashboards`, and typed step sequences (archive old shell-script-mandatory requirement)
- [ ] 8.2 Update `src/main/kotlin/com/rustyrazorblade/easydblab/commands/CLAUDE.md` to document the typed step model and the script fallback
- [ ] 8.3 Update `docs/user-guide/install-clickhouse.md` and `install-presto.md` to reflect that shell scripts are no longer generated; show the new `install.yaml` format
- [ ] 8.4 Add a `docs/user-guide/adding-a-workload.md` page documenting the typed step format, available step types, and how to use the `generate-workload` skill

## 9. Quality

- [ ] 9.1 Run `./gradlew ktlintFormat && ./gradlew test && ./gradlew detekt` and fix any failures
