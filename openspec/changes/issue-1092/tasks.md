## 1. Capabilities and staging

- [ ] 1.1 Ensure `NodeCapability` has `PRIVILEGED` and `HOST_PATH`. #1096 spec's these; whichever change lands first adds the two entries (AWS = `entries.toSet()` picks them up). Do not redefine them if #1096 has landed.
- [ ] 1.2 Change `Stage.requires` from `NodeCapability?` to `Set<NodeCapability>` in `ObservabilityStackService`; emit one `StageSkipped` per missing capability, naming the stage and capability. Unit-test a stage needing two capabilities with one absent.
- [ ] 1.3 Gate the host add-on, Beyla and ebpf_exporter on `{PRIVILEGED}`; journald on `{SYSTEMD_JOURNAL, PRIVILEGED}`.
- [ ] 1.4 Split the Pyroscope eBPF Alloy DaemonSet out of `PyroscopeManifestBuilder.buildAllResources` into its own stage gated on `{PRIVILEGED}`, so the server still reaches Ready and the rollout wait completes without it.

## 2. Core collector (`OtelManifestBuilder`)

- [ ] 2.1 Remove `hostNetwork` and hostPorts 4317/4318 from the core DaemonSet; set `maxSurge: 1, maxUnavailable: 0`; keep tolerating every taint.
- [ ] 2.2 Take a `PodLogSource` (`NodeFiles` | `None`) parameter instead of reading capabilities. `NodeFiles` mounts `/var/log/pods` read-only and renders `logs/containers`; `None` renders no pod-log pipeline, mounts no hostPath and uses a restricted security context. Drop the `DirectoryOrCreate` `/mnt/db1/container-logs` hostPath.
- [ ] 2.3 Replace `resource_detection[system]` with `host.name = ${env:K8S_NODE_NAME}`; rename the `HOSTNAME` env (from `spec.nodeName`) to `K8S_NODE_NAME`; add `K8S_NODE_IP` from `status.hostIP`.
- [ ] 2.4 Add the `kubeletstats` receiver (`https://${env:K8S_NODE_IP}:10250`, `insecure_skip_verify`) on every environment; add RBAC `nodes/stats get`.
- [ ] 2.5 Move host sources (host metrics, host file logs, Beyla/ebpf/Cilium localhost scrapes, `buildCniScrapeJobs`) out of the core config.
- [ ] 2.6 Retarget static NodePort kit jobs from `localhost:<port>` to `${env:K8S_NODE_IP}:<port>`, keeping `instance` = `<node>:<port>`.
- [ ] 2.7 Refactor `buildDaemonSet` so the `@Suppress("LongMethod")` is removed (folded-in debt). Fix with code, not a suppression or threshold change.
- [ ] 2.8 Leave the backend exporter endpoints unchanged — #1091 repoints them to backend Service DNS.

## 3. Service

- [ ] 3.1 Add OTLP HTTP 4318 and `internalTrafficPolicy: Local` to the `otel-collector` Service; keep 4317 and 6831/UDP; select only `app.kubernetes.io/name=otel-collector`.

## 4. Host add-on (`OtelHostCollectorManifestBuilder`, new)

- [ ] 4.1 New builder with its own ConfigMap from a new `otel-host-collector-config.yaml` resource, DaemonSet (hostNetwork, privileged, hostPaths), ServiceAccount. Pod label `app.kubernetes.io/name=otel-collector-host`.
- [ ] 4.2 Receivers: `otlp` on the node `:4317/:4318`, `host_metrics`, `file_log/system|tools|cassandra`, Beyla/ebpf/Cilium localhost scrapes, self-scrape renamed `otel-collector-host`. Every pipeline ends in an `otlp` exporter to `otel-collector.<ns>.svc:4317`. No `memory_limiter`.
- [ ] 4.3 Apply `transform/node_role_from_host` and `host.name = ${env:K8S_NODE_NAME}` to host sources.
- [ ] 4.4 Add the builder to `K8sServiceIntegrationTest.collectAllResources()`, with an apply test, the image-pull test and the no-resource-limits test.

## 5. Shared pieces

- [ ] 5.1 Extract `renderScrapeJobs` into `PrometheusScrapeJob`, used by both builders (folded-in debt).
- [ ] 5.2 Add `OtelEndpoints.serviceHost(namespace) = "otel-collector.$namespace.svc"`, written once.
- [ ] 5.3 Add `OTEL_COLLECTOR_HOST`, `OTEL_OTLP_HTTP_ENDPOINT`, `OTEL_OTLP_GRPC_ENDPOINT` to `TemplateVariables` (`.svc` form).

## 6. Re-point in-cluster producers

- [ ] 6.1 Stress JVM agent (`StressJobService`, `controlNodeIp:4318`) → Service DNS 4318.
- [ ] 6.2 Stress sidecar (`otel-stress-sidecar-config.yaml`, `HOST_IP:4317`) → Service DNS 4317.
- [ ] 6.3 Cassandra sidecar (`SidecarManifestBuilder`, `localhost:4317`) → Service DNS.
- [ ] 6.4 Kits: cassandra-pods and neo4j (`HOST_IP:4318`), ignite3 (`CONTROL_HOST_PRIVATE:4318`) → `__OTEL_OTLP_HTTP_ENDPOINT__`; tidb (`otel-collector.default.svc.cluster.local:6831`) → `__OTEL_COLLECTOR_HOST__`.
- [ ] 6.5 `edl_push_gauges` in `edl-kit-lib.sh` → push through the API-server Service proxy (`.../services/otel-collector:otlp-http/proxy/v1/metrics`) via the workspace kubectl wrapper. Keep it bash 3.2 compatible.
- [ ] 6.6 Add a test that no built-in kit template or kit script contains `HOST_IP:43` or `CONTROL_HOST_PRIVATE:43`.
- [ ] 6.7 Leave host processes (Cassandra on the AMI, profiling reconcile, Fluent Bit, EMR) unchanged — the add-on listens on their endpoint. No packer change.

## 7. `down`

- [ ] 7.1 `K8sTelemetrySenders` takes the ordered list `[otel-collector-host, otel-collector]`; delete-and-wait each under the shared deadline; "already gone" is success. Update `TeardownFlushService` to mark both deleted.
- [ ] 7.2 Test: both DaemonSets deleted and awaited, add-on before core, and an absent add-on does not fail `down`.

## 8. Rollout order

- [ ] 8.1 On an in-place update (`grafana update-config`), wait for the core rollout before applying the host add-on stage, so the add-on does not race old core pods for hostPorts 4317/4318.

## 9. Dashboards (via the `dashboard-editor` agent)

- [ ] 9.1 Use the `dashboard-editor` agent to fix the comparison dashboards' `host_name` filters (e.g. `client-vs-server-latency.json`), since the stress JVM's `host.name` shifts from `control0` to its own node. The agent must edit, deploy and read back from Grafana.

## 10. Tests

- [ ] 10.1 Unit: core config has no host sources and no `memory_limiter`; host-add-on config has no backend exporter and no `memory_limiter`.
- [ ] 10.2 Unit: forwarded host file logs are not counted by the `cassandra.log.*` counters.
- [ ] 10.3 Integration (K3s TestContainers): core reaches Ready with `PodLogSource.None` and a restricted context; add-on forwards host data to the core.

## 11. Docs

- [ ] 11.1 Root `CLAUDE.md` observability section (two collectors, gating, producer endpoints).
- [ ] 11.2 `configuration/CLAUDE.md` OTel subpackage (core vs add-on, new builder, `PodLogSource`).
- [ ] 11.3 `events/CLAUDE.md` for `StageSkipped` per capability.
- [ ] 11.4 User docs: monitoring page notes which telemetry is absent without `PRIVILEGED`/`HOST_PATH`.

## Noted, not in scope

Static NodePort scrape jobs are scraped once per node. This stays a separate issue (owner decision) and is not fixed here.
