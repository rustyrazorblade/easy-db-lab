Do not start until the owner approves the design decisions D1-D6 in `design.md`.

## 1. ConfigMap per dashboard

- [ ] 1.1 In `GrafanaDashboardConfigMapsTest`, write failing tests for:
  - one ConfigMap per file, named `grafana-dashboard-<folder>-<stem>`, whose only key is the file name;
  - both labels;
  - removing one dashboard leaves every other name unchanged;
  - two paths mapping to one name are refused, naming both;
  - a file over budget is refused, naming it;
  - a folder name that is not a DNS-1123 label is refused;
  - one projected volume per folder, `dashboards-<folder>`, mounted read-only at `/var/lib/grafana/dashboards/<folder>`;
  - the home dashboard path lies under a mounted folder;
  - editing one dashboard changes Grafana's config hash.
- [ ] 1.2 Rewrite `GrafanaDashboardConfigMaps` to make them pass. Add `DASHBOARD_CONFIGMAP_LABEL` and `DASHBOARD_CONFIGMAP_PREFIX` to `Constants.Grafana`, and rewrite the `DASHBOARD_CONFIGMAP_MAX_BYTES` KDoc. Update the class KDoc: this is the only delivery path.

## 2. Pruning

- [ ] 2.1 Add `K8sService.pruneConfigMaps(namespace, labels, keep)` and implement it in `DefaultK8sStorageOperations`. It emits `K8s.ConfigMapDeleted` for each map it deletes. Test it against K3s TestContainers:
  - a labelled map not in `keep` is deleted;
  - a labelled map in `keep` survives;
  - an unlabelled map survives.

## 3. One delivery path

- [ ] 3.1 Add `pyroscopeBrowserUrl(publishing, controlHost)` to `profiling/ProfilingConfig.kt`, with a unit test:
  - `NodePorts` gives the private IP and NodePort;
  - `NodePorts` with no control node fails, naming the need;
  - `ClusterIp` gives the Service DNS URL.
- [ ] 3.2 Change `GrafanaDeployService`:
  - `deploy(context, bucket, hosting, pyroscopeUrl)` and `createDatasourcesConfigMap(tenants)` take no `ClusterHost`;
  - drop `withDashboardTree`'s storage branch;
  - render, mount, apply, then prune after the apply loop;
  - change the failure text to "Failed to render Grafana dashboards";
  - update the KDoc.

  Update `GrafanaStage` in `ObservabilityDeployInputs.kt` to resolve the URL and change its failure text. Update every caller.
- [ ] 3.3 Rewrite `GrafanaDeployServiceTest`:
  - drop the uploader;
  - check that the same dashboard ConfigMaps are applied under `HostPath` and under `RetainedClaim`;
  - check that the prune runs after the Deployment apply;
  - check that the Pyroscope URL reaches the rendered profiling dashboard.
- [ ] 3.4 Replace `Grafana.DashboardTreeUploading` and `DashboardTreeUploaded` with `Grafana.DashboardsRendered(count)` in `events/Event.kt`, with console text. Update `EventSerializationTest` and the events CLAUDE.md list if it names them.

## 4. Remove the SSH path

- [ ] 4.1 Delete:
  - `services/GrafanaDashboardTreeUploader.kt`, its Koin binding in `ServicesModule.kt` and `GrafanaDashboardTreeUploaderTest`;
  - `GrafanaDashboardTreeWriter.writeTo`; move `GrafanaDashboardTreeWriterTest` to `render` and fix the writer KDoc;
  - `GrafanaManifestBuilder.GRAFANA_DASHBOARD_HOST_PATH` and the KDoc that says dashboards are not K8s objects.
- [ ] 4.2 Delete `RemoteOperationsService.replaceDirectory`, its implementation, its tests, and the fakes in `TestModules.kt`, `UpTestFixture.kt`, `FakeNodes.kt` and `InstallFileTest.kt`.
- [ ] 4.3 Confirm `ManifestGoldenTest` is unchanged, or update its golden output if the Grafana Deployment's volumes appear in it.

## 5. Integration

- [ ] 5.1 Extend `PortableObservabilityPlacementIntegrationTest` (K3s with Grafana), or add a sibling test. It should deploy twice with two trees and assert:
  - every dashboard is in its folder under its UID, with the pickers set (`/api/search`, `/api/dashboards/uid`);
  - after an edited JSON, Grafana serves the new version when `deploy` returns;
  - after a removed file, its ConfigMap and its dashboard are gone, and every other map keeps its name;
  - a host-path variant gives the same folders and UIDs.
- [ ] 5.2 Run `./gradlew test`, `./gradlew integrationTest` and `./gradlew detekt` (JDK 21) in a subagent. All pass.

## 6. Docs

- [ ] 6.1 Update:
  - `dashboards/CLAUDE.md`, the delivery steps;
  - `src/main/kotlin/.../configuration/CLAUDE.md`, the line about copying the dashboard tree onto a host path;
  - root `CLAUDE.md`, "Grafana services";
  - `docs/reference/commands.md` (`grafana update-config`);
  - `docs/user-guide/monitoring.md`: a dashboard edit restarts Grafana, and dashboards are ConfigMaps labelled `easydblab.com/grafana-dashboard=core`.
- [ ] 6.2 Prove the result on a live cluster with the `dashboard-editor` agent's readback. The owner runs this; no AWS from the spec stage.
