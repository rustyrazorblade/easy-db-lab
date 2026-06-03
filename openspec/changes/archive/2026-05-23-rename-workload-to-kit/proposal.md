## Why

The term "workload" is generic infrastructure jargon that doesn't fit the lab theme of easy-db-lab. "Kit" — as in a lab kit, a packaged set of materials you set up to run an experiment — is more evocative, memorable, and consistent with the project's identity. The rename also gives the descriptor file a distinctive name (`kit.yaml`) that is immediately recognizable as an easy-db-lab concept.

## What Changes

- The concept previously called a "workload" is now called a **kit**
- The descriptor file is renamed from `config.yaml` → `kit.yaml`
- All user-facing CLI output, help text, and error messages replace "workload" with "kit"
- Kotlin source types are renamed: `WorkloadInstallConfig` → `KitConfig`, `WorkloadMetrics` → `KitMetrics`, `WorkloadRuntime` → `KitRuntime`, `WorkloadEndpoint` → `KitEndpoint`, `WorkloadArgSpec` → `KitArgSpec`, `WorkloadHooks` → `KitHooks`, `WorkloadHook` → `KitHook`, `WorkloadHookExecutor` → `KitHookExecutor`, etc.
- **BREAKING**: The descriptor filename changes from `config.yaml` to `kit.yaml`. Any user templates or profile directories using `config.yaml` must rename the file.
- Specs that describe workload behavior are updated to use "kit" terminology
- Spec directories named `workload-*` are renamed to `kit-*`

## Capabilities

### New Capabilities

None — this is a pure rename with no behavioral changes.

### Modified Capabilities

- `install-command`: descriptor filename changes from `config.yaml` to `kit.yaml`; all references to "workload" replaced with "kit"
- `workload-lifecycle-hooks` → `kit-lifecycle-hooks`: rename spec and all terminology
- `workload-metrics-declaration` → `kit-metrics-declaration`: rename spec and all terminology
- `workload-running-state` → `kit-running-state`: rename spec and all terminology
- `workload-uninstall-command` → `kit-uninstall-command`: rename spec and all terminology
- `uninstalled-workload-hint` → `uninstalled-kit-hint`: rename spec and all terminology
- `typed-install-steps`: update all references to "workload" → "kit"
- `platform-substrate`: update all references to "workload" → "kit"

## Impact

- **Kotlin source**: all `Workload*` types in `services/WorkloadInstallConfig.kt` and related files renamed
- **Resource files**: `config.yaml` in all built-in kit template directories renamed to `kit.yaml`
- **CLI loader**: `InstallTemplateResolver` and related services updated to look for `kit.yaml`
- **Specs**: six `workload-*` spec directories renamed and their content updated
- **Docs**: any user-facing documentation referencing "workload" updated to "kit"
- **Tests**: test fixtures and test code updated to match new file name and type names
