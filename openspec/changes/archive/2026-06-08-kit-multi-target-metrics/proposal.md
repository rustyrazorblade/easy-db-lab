## Why

Full instrumentation of every running workload is mandatory. Multi-component databases like
TiDB expose independent Prometheus endpoints per component (TiDB SQL, TiKV, PD, TiFlash) — each
with distinct metric namespaces and operational significance. The current kit `metrics:` field
accepts only one scrape target, making it impossible to collect complete metrics from any
multi-process database without leaving components dark.

## What Changes

- `KitMetrics.Scrape` gains an optional `job` field (defaults to kit name) for naming individual targets
- `KitConfig.metrics` changes from `KitMetrics?` to `List<KitMetrics>` — a list of zero or more metric declarations
- `MetricsRegistryService.register()` accepts a list of scrape targets and creates one ConfigMap per target, labelled with the kit name for bulk deregister
- `MetricsRegistryService.deregister()` deletes all ConfigMaps for a kit by label rather than by single name
- `KitRunnerCommand` passes the full list of `Scrape` entries to the registry on start/stop
- Existing kits with a single `metrics:` block are migrated to list syntax
- TiDB `kit.yaml` is updated to declare all four component scrape targets
- TiDB `nodeport-service.yaml.template` adds NodePorts for TiKV, PD, and TiFlash metrics

## Capabilities

### New Capabilities

_(none)_

### Modified Capabilities

- `kit-metrics-declaration`: The `metrics` field now accepts a list of scrape targets. Each entry in the list becomes an independent OTel scrape job. The `job` sub-field names each scrape job (used as the Prometheus `job` label and ConfigMap suffix); it defaults to the kit name for single-target kits.

## Impact

- `services/KitConfig.kt`: `KitMetrics.Scrape` gains `job` field; `KitConfig.metrics` becomes `List<KitMetrics>`
- `services/MetricsRegistryService.kt`: interface and implementation updated for multi-target registration
- `commands/install/KitRunnerCommand.kt`: passes full list to registry service
- `kits/clickhouse/kit.yaml`, `kits/presto/kit.yaml`: migrated to list syntax (behaviour unchanged)
- `kits/tidb/kit.yaml`: four scrape targets declared (tidb-sql, tikv, pd, tiflash)
- `kits/tidb/nodeport-service.yaml.template`: NodePorts added for TiKV (20180→32180), PD (2379→32379), TiFlash (8234→32234)
- Tests for `MetricsRegistryService` and `KitRunnerCommand` updated
