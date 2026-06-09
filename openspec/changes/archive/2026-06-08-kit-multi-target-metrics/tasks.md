## 1. KitConfig — extend metrics field

- [x] 1.1 Add `val job: String = ""` to `KitMetrics.Scrape` in `services/KitConfig.kt`
- [x] 1.2 Change `val metrics: KitMetrics? = null` to `val metrics: List<KitMetrics> = emptyList()` in `KitConfig`
- [x] 1.3 Verify the existing `installStepModule` `SerializersModule` does not need changes (the `metrics` field is not part of `InstallStep` polymorphism — confirm `KitMetrics` is handled by inline serialization, not the polymorphic module)

## 2. MetricsRegistryService — multi-target registration

- [x] 2.1 Update `MetricsRegistryService` interface: change `register()` signature to accept `targets: List<KitMetrics.Scrape>` instead of `port: Int, path: String`
- [x] 2.2 Update `DefaultMetricsRegistryService.register()`: iterate `targets`; for each, resolve `jobName` as `target.job.ifBlank { kitName }`, create ConfigMap named `easydblab-metrics-<jobName>` with data `job-name`, `port`, `path`, and labels `easydblab.com/workload-metrics=true` AND `easydblab.com/kit=<kitName>`
- [x] 2.3 Update `DefaultMetricsRegistryService.deregister()`: delete all ConfigMaps in `default` namespace matching BOTH labels `easydblab.com/workload-metrics=true` AND `easydblab.com/kit=<kitName>` (label selector delete, not single-name delete)
- [x] 2.4 Verify `K8sService` has a method for label-selector ConfigMap deletion; add one if missing

## 3. KitRunnerCommand — pass full list

- [x] 3.1 In `KitRunnerCommand.handlePostPhase()`, replace the `config.metrics as? KitMetrics.Scrape` cast with `config.metrics.filterIsInstance<KitMetrics.Scrape>()` and pass the resulting list to `metricsRegistryService.register()`

## 4. Migrate existing kit.yamls to list syntax

- [x] 4.1 Update `kits/clickhouse/kit.yaml`: convert `metrics:` single object to a one-element list
- [x] 4.2 Update `kits/presto/kit.yaml`: convert `metrics:` single object to a one-element list

## 5. TiDB — full component metrics

- [x] 5.1 Update `kits/tidb/kit.yaml`: replace the single `metrics:` block with a four-entry list — `tidb-sql` (port 31080), `tikv` (port 32180), `pd` (port 32379), `tiflash` (port 32234)
- [x] 5.2 Update `kits/tidb/nodeport-service.yaml.template`: add NodePort entries for TiKV metrics (20180→32180), PD metrics (2379→32379), and TiFlash metrics (8234→32234); these require separate Services with the correct pod selectors (`app.kubernetes.io/component: tikv`, `pd`, `tiflash`)
- [x] 5.3 Add the three new NodePort Services to `kits/tidb/kit.yaml` stop phase (delete them alongside the existing `tidb-nodeport` deletion)

## 6. Tests

- [x] 6.1 Update `MetricsRegistryService` tests: pass `List<KitMetrics.Scrape>` to `register()`, assert one ConfigMap created per target with correct names and labels; assert `deregister()` deletes by label selector
- [x] 6.2 Update `KitRunnerCommand` tests (if they exist) to reflect the list-based call to `metricsRegistryService.register()`
- [x] 6.3 Add/update `KitConfig` parsing test: verify a multi-target `metrics:` list with `job` fields deserializes correctly

## 7. Verification

- [x] 7.1 Run `./gradlew ktlintFormat && ./gradlew compileKotlin` — confirm no compilation errors
- [x] 7.2 Run `./gradlew test` — confirm all existing tests pass
