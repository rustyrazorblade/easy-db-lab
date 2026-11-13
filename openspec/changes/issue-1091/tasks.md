## 1. Placement value

- [x] 1.1 Add a `Placement` data class (node selector, tolerations, host-network flag) and resolve it from the recorded environment/capabilities alongside `NodeCapabilities.from(ClusterState)`. AWS resolves to the control-node selector + toleration and `hostNetwork=false`; elsewhere empty selector/tolerations. Merge an `init` user node label into the selector. Unit test: AWS resolution carries the control-plane selector + toleration; an empty environment carries neither; a user label merges in. No consumer tests an environment name.

## 2. Backends and Grafana on the pod network

- [ ] 2.1 Test first: rendering test that with the AWS placement each of Mimir, Loki, Tempo, Pyroscope, Grafana carries the `control-plane` selector + toleration and the `/mnt/db1/<backend>` hostPath, identical to today; with an empty placement none carries a selector/toleration and none sets `hostNetwork`.
- [ ] 2.2 Remove the hardcoded `addToNodeSelector(... control-plane ...)`, the control-plane toleration and `withHostNetwork(true)` from `MimirManifestBuilder`, `LokiManifestBuilder`, `TempoManifestBuilder`, `PyroscopeManifestBuilder`, `GrafanaManifestBuilder`; apply the resolved `Placement` instead. `ObservabilityStackService` passes one `Placement` into each builder.
- [ ] 2.3 Swap kube-state-metrics' control-node selector to the same resolved `Placement` (it already runs pod-network ClusterIP).

## 3. Backend storage

- [ ] 3.1 Test first: with `HOST_PATH` present each backend mounts its `/mnt/db1/<backend>` host path; without it each mounts a per-backend claim whose StorageClass reclaims with `Retain`, and none uses `emptyDir`.
- [ ] 3.2 Wire each backend's volume source from the resolved storage: hostPath where `HOST_PATH`, otherwise a retained claim (StatefulSet `volumeClaimTemplates`, or Deployment + PVC with the existing `Recreate` strategy). Feature-gate the off-AWS branch behind a stub until #1035's `ObservabilityStorage` lands; the AWS hostPath branch is fully delivered.

## 4. Service DNS for in-cluster callers

- [ ] 4.1 Test first: Grafana datasources built for a cluster use each backend's Service DNS URL (`<svc>.<namespace>.svc:<port>`), not `localhost`.
- [ ] 4.2 Change `GrafanaDatasourceSet`/`GrafanaDeployService` backend base URLs to Service DNS.
- [ ] 4.3 Test first: the rendered OTel collector config exports to the backends by Service DNS, not `localhost`.
- [ ] 4.4 Change the collector's backend exporter endpoints (`OtelManifestBuilder` `LOCAL_*`) to Service DNS. Coordinate: collector Service name `otel-collector` (#1092).

## 5. Grafana documents same-origin

- [ ] 5.1 Test first: the documents web server is exposed as a second port on the grafana Service with no host port, and the documents URL used by dashboards is same-origin/relative.
- [ ] 5.2 Remove the documents host port from `GrafanaDocumentsSidecars`; add the documents port to #1090's grafana Service (do not add a second grafana Service here). Make `DashboardDefaults.documentsUrl` same-origin/relative. The sigv4 proxy uses the default credential chain.

## 6. Credential plug points

- [ ] 6.1 Give each backend workload a `serviceAccountName` (default) and an optional `envFrom` store-credentials Secret mount, absent on AWS. (Population is #1061.)

## 7. Integration test and docs

- [ ] 7.1 Integration test (`src/integrationTest/`, K3s TestContainer): delete the `node-role.kubernetes.io/control-plane` label on the node, deploy with an empty `Placement`, assert every backend and Grafana reaches Ready and the datasource URLs are Service DNS.
- [ ] 7.2 Update the root `CLAUDE.md` observability section and `configuration/CLAUDE.md`: backends/Grafana on the pod network with per-environment placement, Service-DNS in-cluster callers, retained claims off owned nodes, documents same-origin. Note the #1090 sequencing and that #1090 owns the grafana Service.
- [ ] 7.3 Run `./gradlew check` on JDK 21 in a subagent, with no new `@Suppress`, detekt threshold change or baseline entry.
