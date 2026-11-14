## 1. Placement value

- [x] 1.1 Add a `Placement` data class (node selector, tolerations, host-network flag) and resolve it from the recorded environment/capabilities alongside `NodeCapabilities.from(ClusterState)`. AWS resolves to the control-node selector + toleration and `hostNetwork=false`; elsewhere empty selector/tolerations. Merge an `init` user node label into the selector. Unit test: AWS resolution carries the control-plane selector + toleration; an empty environment carries neither; a user label merges in. No consumer tests an environment name.

## 2. Backends and Grafana on the pod network

- [x] 2.1 Test first: rendering test that with the AWS placement each of Mimir, Loki, Tempo, Pyroscope, Grafana carries the `control-plane` selector + toleration and the `/mnt/db1/<backend>` hostPath, identical to today; with an empty placement none carries a selector/toleration and none sets `hostNetwork`.
- [x] 2.2 Remove the hardcoded `addToNodeSelector(... control-plane ...)`, the control-plane toleration and `withHostNetwork(true)` from `MimirManifestBuilder`, `LokiManifestBuilder`, `TempoManifestBuilder`, `PyroscopeManifestBuilder`, `GrafanaManifestBuilder`; apply the resolved `Placement` instead. `ObservabilityStackService` passes one `Placement` into each builder.
- [x] 2.3 Swap kube-state-metrics' control-node selector to the same resolved `Placement` (it already runs pod-network ClusterIP).

## 3. Backend storage

- [x] 3.1 Test first: with `HOST_PATH` present each backend mounts its `/mnt/db1/<backend>` host path; without it each mounts a per-backend claim whose StorageClass reclaims with `Retain`, and none uses `emptyDir`.
- [x] 3.2 Wire each backend's volume source from the resolved storage: hostPath where `HOST_PATH`, otherwise a retained claim (StatefulSet `volumeClaimTemplates`, or Deployment + PVC with the existing `Recreate` strategy). Feature-gate the off-AWS branch behind a stub until #1035's `ObservabilityStorage` lands; the AWS hostPath branch is fully delivered.

## 4. Service DNS for in-cluster callers

- [x] 4.1 Test first: Grafana datasources built for a cluster use each backend's Service DNS URL (`<svc>.<namespace>.svc:<port>`), not `localhost`.
- [x] 4.2 Change `GrafanaDatasourceSet`/`GrafanaDeployService` backend base URLs to Service DNS.
- [x] 4.3 Test first: the rendered OTel collector config exports to the backends by Service DNS, not `localhost`.
- [x] 4.4 Change the collector's backend exporter endpoints (`OtelManifestBuilder` `LOCAL_*`) to Service DNS. Coordinate: collector Service name `otel-collector` (#1092).

## 5. Grafana documents same-origin

- [x] 5.1 Test first: through Grafana's one client port, a `GET` under `/reports/` reaches the signing proxy (stubbed), a write under `/reports/` is refused, a Grafana API path and the Live websocket reach Grafana (`GrafanaFrontIntegrationTest`, K3s); the documents URL the dashboards get is relative (`DashboardDefaultsTest`).
- [x] 5.2 Revised decision B: the documents nginx (`GrafanaFront`) fronts Grafana on port 3000 and Grafana moves to `127.0.0.1:${Constants.Grafana.INTERNAL_PORT}`; host port 3080 and the separate documents port are removed; `DashboardDefaults` makes the documents URL relative. #1090's grafana Service needs no change. The sigv4 proxy uses the default credential chain.

## 6. Credential plug points

- [x] 6.1 Give each backend workload a `serviceAccountName` (default) and an optional `envFrom` store-credentials Secret mount, absent on AWS. (Population is #1061.)

## 7. Integration test and docs

- [x] 7.1 Integration test (`src/integrationTest/`, K3s TestContainer): delete the `node-role.kubernetes.io/control-plane` label on the node, deploy with an empty `Placement`, assert every backend and Grafana reaches Ready and the datasource URLs are Service DNS.
- [x] 7.2 Update the root `CLAUDE.md` observability section and `configuration/CLAUDE.md`: backends/Grafana on the pod network with per-environment placement, Service-DNS in-cluster callers, retained claims off owned nodes, documents same-origin through the nginx front. Note the #1090 sequencing and that #1090 owns the grafana Service.
- [ ] 7.3 Run `./gradlew check` on JDK 21 in a subagent, with no new `@Suppress`, detekt threshold change or baseline entry.
