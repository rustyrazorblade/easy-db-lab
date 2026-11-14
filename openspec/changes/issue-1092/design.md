# Design

## Context

Today one DaemonSet (`OtelManifestBuilder.kt`) runs with `hostNetwork`, hostPorts 4317/4318 and hostPaths, collects everything, and exports to the backends. On a cluster that rejects privileged/hostNetwork pods the whole thing is rejected and no telemetry arrives. This change separates what genuinely needs the host from what does not.

`NodeCapability` on this branch (`133c4661`) has only `SYSTEMD_JOURNAL`, `CLOUDWATCH`, `S3` (`NodeCapability.kt`). `PRIVILEGED` and `HOST_PATH` are added by #1096; whichever change lands first adds the two enum entries, and `AWS = entries.toSet()` picks them up. Beyla and ebpf_exporter are not gated today (`ObservabilityStackService.kt`).

## Goals

- Pod and application telemetry flows wherever the core can run, with no privilege.
- No change to what is collected on AWS, and no AMI/packer change.
- Each absent capability is reported with `StageSkipped`, by name.

## Decisions

- **D1 — the host add-on forwards to the core via the Service.** Exporters, tenant headers, connectors and the EMR `host.name` transforms stay in one place (the core). The add-on's pipelines end in an `otlp` exporter to `otel-collector.<ns>.svc:4317`.
- **D2 — the add-on owns the node's `:4317/:4318` listener.** Host processes that already dial the node (Cassandra on the VM `cassandra.in.sh`, `edl-profiling-reconcile`, Fluent Bit, EMR exporting to the control node) keep their endpoint, so no AMI rebake. These processes only exist on nodes easy-db-lab owns, which always have `PRIVILEGED`. The add-on's `otlp` receiver re-emits to the core through the Service.
- **D3 — two builders, two ConfigMaps.** `OtelManifestBuilder` (core) and a new `OtelHostCollectorManifestBuilder`. Each has one responsibility, its own `ConfigHashAnnotator` hash, and its own capability gate. One builder rendering two configs would couple the gates.
- **D4 — defer `k8s_events`.** A DaemonSet would emit each event once per node; a correct singleton (leader-elect or a Deployment) is a follow-up.
- **D5 — `kubeletstats` everywhere.** It only adds series and none collide, so it runs on every environment, including AWS.
- **D6 — fold in the Pyroscope eBPF stage split and `Stage.requires: Set<NodeCapability>`.** Without the set, a stage cannot require both `SYSTEMD_JOURNAL` and `PRIVILEGED`. Without splitting the Pyroscope eBPF Alloy DaemonSet out of `PyroscopeManifestBuilder.buildAllResources`, a rejected Alloy pod fails `waitForRollouts` and the AC "core reaches Ready without PRIVILEGED" fails.

## Core vs. host add-on

| Source today | Goes to | Notes |
|---|---|---|
| `otlp`, `jaeger` receivers | both | Core serves pods and is the Service backend; add-on is a pass-through `otlp → batch → otlp/core`. |
| `host_metrics` (`/hostfs`) | add-on | Unchanged. |
| `file_log/system`, `file_log/tools`, `file_log/cassandra` (GC log, `/mnt/db1`) | add-on | Unchanged. |
| `file_log/containers`, `file_log/clickhouse-containers` | core, only with `HOST_PATH` | Mount `/var/log/pods` read-only; the kubelet resolves the NVMe symlink, so the `DirectoryOrCreate` hostPath on `/mnt/db1/container-logs` goes away. |
| Beyla `localhost`, ebpf_exporter `localhost` | add-on | Unchanged; both already require `PRIVILEGED`. |
| Self-scrape `localhost:8888` | both | Add-on's job renamed `otel-collector-host` to avoid duplicate series. |
| Cilium agent / Hubble `localhost` scrapes; Cilium operator pod-SD | add-on | Kept on localhost. Cilium exists only on the K3s we build, which always has `PRIVILEGED`, so AWS is unchanged. `buildCniScrapeJobs` moves to the add-on builder. |
| Pod-SD jobs: kube-state-metrics, backends, YACE, kit pod-SD | core | Pod/node IP is reachable from the pod network. |
| Static NodePort kit jobs (`localhost:<port>`) | core | Target `${env:K8S_NODE_IP}:<port>`; `instance` stays `<node>:<port>`. |
| `kubeletstats` (new) | core | `https://${env:K8S_NODE_IP}:10250`, verified with `ca_file` = the service-account `ca.crt` (K3s signs each kubelet serving cert with that CA, node IP in the SANs); adds RBAC `nodes/stats`. |
| `k8s_attributes`, connectors, exporters | core | |
| `resource_detection[system]` (os hostname) | replaced | `host.name = ${env:K8S_NODE_NAME}` in core and add-on, since the OS hostname on the pod network is the pod name. |
| `transform/node_role_from_host`, EMR `keep/restore_emr_host` | core (OTLP) + add-on (host sources) | Node role derived from `host.name`. |

Node-local pod-SD keeps working: the DaemonSet already overrides `HOSTNAME` from `spec.nodeName`; it is renamed `K8S_NODE_NAME` so the pod name does not mislead a later reader.

## Boundaries vs. in-flight issues

- **This change** edits the receiver / pipeline / Service / DaemonSet side of `OtelManifestBuilder`, adds `OtelHostCollectorManifestBuilder`, and changes `ObservabilityStackService` staging and `TelemetrySenders`.
- **#1091** repoints the collector's backend exporter endpoints (`localhost:<port>` → Mimir/Loki/Tempo Service DNS) and owns the `default`-namespace hard-codes for the backends. This change leaves exporter endpoints as they are so it does not conflict; after both land the core exports to backend Service DNS.
- **#1096** owns the `PRIVILEGED`/`HOST_PATH` enum entries and the probe. This change consumes them by name.
- **#1089 (PR #1107)** changes the K8s client construction (`TelemetrySenders` rebase on the `ClusterHost` signature). Depended on.
- **#1094** owns the namespace parameter; this change uses the `.svc` short DNS form (resolves through the pod search path, no `cluster.local` assumption).
- **#1095** sets restricted contexts broadly; this change sets only the core collector's context.

## Structure

- **`OtelManifestBuilder` (core):** ServiceAccount, ClusterRole (adds `nodes/stats`), ConfigMap, DaemonSet (no hostPort; `maxSurge: 1, maxUnavailable: 0`; tolerates every taint), Service (adds 4318 and `internalTrafficPolicy: Local`). Takes a `PodLogSource` (`NodeFiles` | `None`) instead of reading capabilities, keeping builders capability-agnostic (`configuration/CLAUDE.md`). Refactor `buildDaemonSet` to drop `@Suppress("LongMethod")`.
- **`OtelHostCollectorManifestBuilder` (new):** its own `otel-host-collector-config.yaml` ConfigMap, DaemonSet (hostNetwork, privileged, hostPaths), ServiceAccount. Env: `K8S_NODE_NAME`, `CLUSTER_NAME`, the core endpoint. Carries `buildCniScrapeJobs`. Pods labelled `app.kubernetes.io/name=otel-collector-host` so the Service does not select them.
- **Shared:** extract `renderScrapeJobs` to `PrometheusScrapeJob`; add `OtelEndpoints.serviceHost(namespace)`.
- **`ObservabilityStackService`:** `Stage.requires: Set<NodeCapability>`; one `StageSkipped` per missing capability. New/changed stages: host add-on `{PRIVILEGED}`, Beyla `{PRIVILEGED}`, ebpf_exporter `{PRIVILEGED}`, journald `{SYSTEMD_JOURNAL, PRIVILEGED}`, pod logs `{HOST_PATH}`, Pyroscope eBPF agent `{PRIVILEGED}` (split from `buildAllResources`).
- **`CollectorResources` / `OtelSyncService`:** stay core-only (kit registry drives only the core config). `ConfigHashAnnotator` / `ConfigChangeReport` are per-resource and the two ConfigMaps differ by name, so no change.
- **`TemplateVariables`:** add `OTEL_COLLECTOR_HOST`, `OTEL_OTLP_HTTP_ENDPOINT`, `OTEL_OTLP_GRPC_ENDPOINT` (`.svc` form).
- **`K8sTelemetrySenders`:** ordered `[otel-collector-host, otel-collector]`, delete-and-wait each under the shared deadline; "already gone" counts as success.

## Risks / trade-offs

- **`host.name` of the stress JVM** shifts from `control0` to its own node; comparison dashboards' `host_name` filters are fixed via the dashboard-editor agent (Q2).
- **Port race on in-place update** (`grafana update-config`): old core pods hold hostPorts 4317/4318 while the add-on starts, so the add-on CrashLoops binding them. Mitigation: wait for the core rollout before applying the add-on stage. A fresh `up` is unaffected.
- **`internalTrafficPolicy: Local`** drops traffic on a node with no core pod; mitigated by tolerating every taint and `maxSurge`. Fargate-style nodeless scheduling is not supported.
- **Host file logs now traverse the core's `logs/otlp` and connectors** — they carry no severity/scope, so the `cassandra.log.*` counters must ignore them (guarded by a test).
- **Duplicate series** avoided by distinct self-scrape job names and one source per collector.
