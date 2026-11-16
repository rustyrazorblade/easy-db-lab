## Tasks

### Core model

- [x] Add `KitCommandSpec` data class to `KitConfig.kt` with `description: String` and `args: List<KitArgSpec>` fields, annotated with `@Serializable`
- [x] Add `commands: Map<String, KitCommandSpec> = emptyMap()` field to `KitConfig` data class

### KitRunnerCommandFactory wiring

- [x] Pass `installConfig: KitConfig` into `buildPhaseCommand()` (update signature and all call sites in `buildKitGroup`)
- [x] In `buildPhaseCommand`, look up `installConfig.commands[phaseName]`; if present, register its args as `OptionSpec` entries on the command spec using the same `ISetter`→`runtimeArgValues` pattern as `KitInstallCommandFactory.argOptionSpec()`
- [x] Guard against `--version` conflict: if the command's args include `--version`, skip `mixinStandardHelpOptions` and add only `--help` manually (same guard already in `KitInstallCommandFactory`)

### KitRunnerCommand env layering

- [x] Add `runtimeArgValues: MutableMap<String, String> = mutableMapOf()` field to `KitRunnerCommand`
- [x] In `buildAugmentedEnv`, insert `runtimeArgValues` between `resolvedArgs` and `base` in the env map: `argDefaults + resolvedArgs + runtimeArgValues + base + KUBECONFIG + BACKUP_NAME + targetVars`

### Kit migrations — kit.yaml

- [x] `kafka/kit.yaml`: add `commands:` block with `producer-perf` (NUM_RECORDS, RECORD_SIZE, THROUGHPUT, TOPIC), `consumer-perf` (NUM_RECORDS, TOPIC, GROUP), `create-topic` (TOPIC, PARTITIONS, REPLICATION_FACTOR)
- [x] `sysbench/kit.yaml`: move `--threads`, `--duration`, `--workload` from top-level `args:` into `commands: start: args:`; keep `--target` in top-level `args:` (install-time kit-ref)
- [x] `clickhouse/kit.yaml`: audit args — all args are deployment config, no migration needed
- [x] `presto/kit.yaml`: audit args — all args are deployment config, no migration needed
- [x] `tidb/kit.yaml`: audit args — all args are deployment config, no migration needed

### Tests

- [x] `KitRunnerCommandFactoryTest`: add test — `buildPhaseCommand` for a command with declared args registers the correct `OptionSpec` entries on the returned `CommandLine`
- [x] `KitRunnerCommandFactoryTest`: add test — command with no `commands:` entry has no extra options beyond `--help` and `--name`
- [x] `KitRunnerCommandTest`: add test — runtime arg value provided via CLI is present in the env map passed to the script, overriding the install-time resolved value for the same variable
- [x] `KitRunnerCommandTest`: add test — runtime arg default is used when flag is not provided (verified via OptionSpec.defaultValue in factory test)
- [x] `KitRunnerCommandTest`: add test — cluster state var is not shadowed by a runtime arg with the same variable name
- [x] `KitConfigTest` (or equivalent): add test — `KitCommandSpec` round-trips through YAML serialization correctly
