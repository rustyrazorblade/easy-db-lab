## Why

Core dashboards reach Grafana by an SSH upload into the Grafana host path on the control node (`services/GrafanaDashboardTreeUploader.kt`). A cluster the user brings (EKS, `init --kubeconfig`, #1082) gives easy-db-lab no SSH, so that path cannot work there. Epic #1032 (Kubernetes-forward) requires that no cluster content travels over SSH (`docs/plans/2026-11-11-kubernetes-forward-architecture.md`, "No SSH for cluster content").

A Kubernetes API path already exists: on a retained claim, `GrafanaDashboardConfigMaps` packs the rendered tree into `grafana-dashboard-tree-<n>` ConfigMaps that the Grafana Deployment mounts. Only host-path storage (AWS) still uses SSH.

## What Changes

- Core dashboards reach Grafana as ConfigMaps on every environment. The SSH upload is removed. This is proposed, pending owner approval (design D1).
- One ConfigMap per dashboard file, named `grafana-dashboard-<folder>-<stem>`, with one key (the file name). It is labelled `easydblab.com/grafana-dashboard: core` as well as `app.kubernetes.io/name: grafana`. This replaces the packed `grafana-dashboard-tree-<n>` maps (D2, D3).
- One read-only projected volume per folder, named `dashboards-<folder>`, mounted at `/var/lib/grafana/dashboards/<folder>`. The file provider, `foldersFromFilesStructure`, the folder names, the dashboard UIDs and the home dashboard path are unchanged.
- The Grafana config hash keeps covering the dashboard ConfigMaps, so a dashboard edit rolls Grafana. `up` and `grafana update-config` return once Grafana shows the change (D4).
- After the Grafana Deployment is applied, every ConfigMap with the dashboard label that is not in the rendered set is deleted (new `K8sService.pruneConfigMaps`).
- `DashboardDefaults` still runs on every install path. It runs inside `GrafanaDashboardTreeWriter.render`, which is now the only way a tree is produced.
- The "Pyroscope UI" link URL depends on how the environment publishes services, not on where it stores state. It is the NodePort on the control node's private IP where NodePorts are published, and the Service DNS name otherwise. `GrafanaDeployService` no longer takes a `ClusterHost` (D5b).
- Removed: `GrafanaDashboardTreeUploader` and its Koin binding, `GrafanaDashboardTreeWriter.writeTo`, `GrafanaManifestBuilder.GRAFANA_DASHBOARD_HOST_PATH`, and `RemoteOperationsService.replaceDirectory` with its implementation and fakes. The last has no other caller (D5a).
- Events: `Grafana.DashboardTreeUploading` / `DashboardTreeUploaded` are replaced by one `Grafana.DashboardsRendered(count)`. A prune emits the existing `K8s.ConfigMapDeleted` (D6).
- Docs: `dashboards/CLAUDE.md`, `configuration/CLAUDE.md`, root `CLAUDE.md` ("Grafana services"), `docs/reference/commands.md`, and `docs/user-guide/monitoring.md` (a dashboard edit restarts Grafana).

## Scope

In: core dashboards on `up` and `grafana update-config`, on every environment.

Out:
- `grafana install` of one dashboard. It already uses the HTTP API.
- Kit dashboards. They stay on `KitRunnerCommand` through the Grafana API.
- The other SSH steps of `up` on AWS, such as `OnNodeStateDirectories` preparing `/mnt/db1` directories. They are other issues of the epic.
- The Pyroscope link on `ClusterIp` environments. It stays the in-cluster name, as today.

## Capabilities

### Modified Capabilities

- `observability`: "Grafana Dashboards". Delivery is through ConfigMaps on every environment. The SSH copy and replace scenario is replaced by scenarios for an edit, a removal, refusal of a bad file, the Pyroscope link and delivery with no SSH.
- `observability`: "Observability backends and Grafana run on the pod network…". The claim-only dashboard scenario covers every storage mode.

## Impact

- Code:
  - `configuration/grafana/GrafanaDashboardConfigMaps.kt`
  - `configuration/grafana/GrafanaDashboardTreeWriter.kt`
  - `configuration/grafana/GrafanaManifestBuilder.kt`
  - `services/GrafanaDeployService.kt`
  - `services/ObservabilityDeployInputs.kt` (`GrafanaStage`)
  - `profiling/ProfilingConfig.kt`
  - `services/K8sService.kt` and `DefaultK8sStorageOperations.kt`
  - `providers/ssh/RemoteOperationsService.kt` and its implementation
  - `events/Event.kt`
  - `Constants.kt`
  - `services/ServicesModule.kt`
- Tests:
  - Delete `GrafanaDashboardTreeUploaderTest`.
  - Rewrite `GrafanaDashboardConfigMapsTest` and `GrafanaDeployServiceTest`.
  - Move `GrafanaDashboardTreeWriterTest` to `render`.
  - Extend the K3s integration test, `PortableObservabilityPlacementIntegrationTest`, for an edit, a removal and host-path storage.
  - Remove the `replaceDirectory` fakes.
- Behavior: a dashboard edit now restarts Grafana on AWS too (`Recreate`, a few seconds). `up` already waits for the rollout.
- Spec coordination: the in-flight change `issue-1064` also modifies "Grafana Dashboards". This delta is written on 1064's text of that requirement (its `en.*` network-panel line), so archiving either change first loses nothing.
