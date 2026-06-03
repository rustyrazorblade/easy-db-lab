## 1. Kotlin Type Renames

- [x] 1.1 Rename `WorkloadInstallConfig` → `KitConfig` in `services/WorkloadInstallConfig.kt` and rename the file to `KitConfig.kt`
- [x] 1.2 Rename `WorkloadMetrics` → `KitMetrics` (and all subclasses: `Scrape`, `JavaAgent`, `HelmNative`)
- [x] 1.3 Rename `WorkloadRuntime` → `KitRuntime` (and `RuntimeType`)
- [x] 1.4 Rename `WorkloadEndpoint` → `KitEndpoint` (and `EndpointType`)
- [x] 1.5 Rename `WorkloadArgSpec` → `KitArgSpec` (and `ArgType`)
- [x] 1.6 Rename `WorkloadHooks` → `KitHooks`, `WorkloadHook` → `KitHook`
- [x] 1.7 Rename `WorkloadHookExecutor` → `KitHookExecutor` and update all usages
- [x] 1.8 Update all import sites and usages of renamed types across the codebase

## 2. Descriptor Filename

- [x] 2.1 Update `InstallTemplateResolver` to look for `kit.yaml` instead of `config.yaml`
- [x] 2.2 Rename `config.yaml` → `kit.yaml` in all built-in classpath kit template directories (`clickhouse`, `presto`, any others)
- [x] 2.3 Update `CommandLineParser` dynamic subcommand registration to scan for `kit.yaml`
- [x] 2.4 Update all references to `config.yaml` in CLI help text and error messages

## 3. Template Variable

- [x] 3.1 Rename `__WORKLOAD_NAME__` → `__KIT_NAME__` in `TemplateVariables` and all template files
- [x] 3.2 Rename `RUNNING_WORKLOADS` env var → `RUNNING_KITS` in template injection and all built-in `bin/` scripts

## 4. Event Types

- [x] 4.1 Rename `Event.Workload` domain → `Event.Kit` and all subtypes (`ScriptStarted`, `ScriptFinished`, `HookFailed`, etc.)
- [x] 4.2 Update all `eventBus.emit(Event.Workload.*)` call sites

## 5. CLI Output and Help Text

- [x] 5.1 Update all user-facing strings in commands, services, and error messages: "workload" → "kit"
- [x] 5.2 Update `ClusterState.runningWorkloads` field → `runningKits`

## 6. Spec Folder Renames

- [x] 6.1 Rename `openspec/specs/workload-lifecycle-hooks/` → `kit-lifecycle-hooks/` and update content
- [x] 6.2 Rename `openspec/specs/workload-metrics-declaration/` → `kit-metrics-declaration/` and update content
- [x] 6.3 Rename `openspec/specs/workload-running-state/` → `kit-running-state/` and update content
- [x] 6.4 Rename `openspec/specs/workload-uninstall-command/` → `kit-uninstall-command/` and update content
- [x] 6.5 Rename `openspec/specs/uninstalled-workload-hint/` → `uninstalled-kit-hint/` and update content
- [x] 6.6 Update all remaining "workload" references in `openspec/specs/install-command/spec.md`, `platform-substrate/spec.md`, and `typed-install-steps/spec.md`

## 7. Tests and Fixtures

- [x] 7.1 Rename test YAML fixtures from `config.yaml` → `kit.yaml`
- [x] 7.2 Update all test references to renamed Kotlin types
- [x] 7.3 Update `UninstalledWorkloadHintHandlerTest` → `UninstalledKitHintHandlerTest`
- [x] 7.4 Run full test suite and confirm all tests pass

## 8. Documentation

- [x] 8.1 Update `docs/` references: "workload" → "kit", `config.yaml` → `kit.yaml`
- [x] 8.2 Update `CLAUDE.md` and subdirectory CLAUDE.md files that reference "workload" terminology
