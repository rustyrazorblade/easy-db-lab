## Context

The metrics registry works via K8s ConfigMaps: on `start`, `MetricsRegistryService` writes one
ConfigMap (`easydblab-metrics-<kit>`) labelled `easydblab.com/workload-metrics=true`.
`OtelManifestBuilder.listWorkloadScrapeConfigs()` reads all such ConfigMaps and generates one
Prometheus scrape job per ConfigMap in the OTel collector config. The OTel DaemonSet (hostNetwork)
scrapes `localhost:<nodePort>` for each job.

The bottleneck is `MetricsRegistryService.register()` — it takes a single port/path and creates
a single ConfigMap. Everything downstream (`OtelManifestBuilder`, the ConfigMap watcher) already
handles multiple ConfigMaps naturally; only the registration layer needs to change.

## Goals / Non-Goals

**Goals:**
- Support any number of Prometheus scrape targets per kit, each as an independent OTel scrape job
- Deregister ALL targets for a kit atomically on stop
- Zero behaviour change for existing single-target kits
- Full metrics coverage for TiDB: all four components scraped

**Non-Goals:**
- Kubernetes pod-level service discovery (scraping by pod IP via k8s_sd_configs) — the NodePort
  pattern is consistent with how existing workloads are scraped and sufficient for lab use
- Per-node scraping affinity for multi-replica components — NodePort routes to any matching pod,
  which is acceptable in a lab context where aggregate metrics matter more than per-node isolation

## Decisions

### `metrics` becomes `List<KitMetrics>` in `KitConfig`

Changing the field from a nullable singleton to a list is the cleanest representation and avoids
a parallel `metrics-endpoints` field. Existing kit.yamls must migrate from:
```yaml
metrics:
  type: scrape
  port: 9090
```
to:
```yaml
metrics:
  - type: scrape
    port: 9090
```
There are only two affected kits (clickhouse, presto) plus tidb. Migration is mechanical.
Clusters are ephemeral so there is no compatibility concern.

### `KitMetrics.Scrape` gains a `job` field (optional, defaults to kit name)

The `job` field names the Prometheus scrape job and the ConfigMap suffix. For single-target
kits the field is omitted and the kit name is used, preserving existing behaviour. For
multi-target kits, each target must declare a distinct `job`:
```yaml
metrics:
  - type: scrape
    port: 31080
    job: tidb-sql
  - type: scrape
    port: 32180
    job: tikv
```
This is the Prometheus `job` label that appears in VictoriaMetrics — choosing descriptive names
here (rather than kit-scoped names like `tidb-tidb-sql`) makes dashboard queries readable.

### ConfigMaps named `easydblab-metrics-<job>`, labelled with kit name

Multi-target kits need multiple ConfigMaps. Naming them by job (`easydblab-metrics-tikv`)
rather than kit (`easydblab-metrics-tidb-1`) keeps names readable. A label
`easydblab.com/kit: <kitName>` is added to every ConfigMap so `deregister()` can delete all
of a kit's ConfigMaps by label selector in a single API call rather than iterating known names.

**Alternative considered**: naming by index (`easydblab-metrics-tidb-0`, `easydblab-metrics-tidb-1`).
Rejected — job names are more stable and readable in `kubectl get configmaps` output.

### `MetricsRegistryService` interface changes

`register()` signature changes from `(controlHost, kitName, port, path)` to
`(controlHost, kitName, targets: List<KitMetrics.Scrape>)`. Call sites (only `KitRunnerCommand`)
are updated accordingly.

### TiDB NodePorts for component metrics

Four NodePorts expose each component's Prometheus endpoint on the host network:

| Component | Container port | NodePort |
|-----------|---------------|----------|
| TiDB SQL  | 10080         | 31080 (existing) |
| TiKV      | 20180         | 32180 |
| PD        | 2379          | 32379 |
| TiFlash   | 8234          | 32234 |

TiKV, PD, and TiFlash require separate NodePort Services (distinct pod selectors per component).
The existing `tidb-nodeport` Service selects only TiDB SQL pods and is extended only for
the TiDB SQL MySQL and metrics ports it already exposes.

## Risks / Trade-offs

- *NodePort routing for multi-replica components*: When multiple TiKV pods exist (one per db node),
  `localhost:32180` from the OTel DaemonSet routes via kube-proxy to any TiKV pod. This means a
  given OTel collector may not scrape its co-located TiKV pod exclusively. For lab-scale clusters,
  this is acceptable — all pod metrics are eventually collected across the DaemonSet fleet.
  [Mitigation]: Future improvement could add k8s_sd_configs-based pod discovery to scrape by
  pod IP, ensuring each collector scrapes only its local pod.

- *`metrics` field is now a list*: Existing kit.yamls are updated as part of this change. Any
  external or custom kit.yamls not updated will fail to parse (field type mismatch). Since kits
  are classpath-bundled, all shipped kits are updated atomically with this change.
