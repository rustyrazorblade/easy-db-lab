## Context

`up` (through `UpPostSteps`) and `grafana update-config` (`commands/grafana/GrafanaUpdateConfig.kt:35`) both reach `ObservabilityStackService.deploy()`. That calls `GrafanaStage.deploy` (`services/ObservabilityDeployInputs.kt:47-60`), which calls `GrafanaDeployService.deploy` (`services/GrafanaDeployService.kt:101`). So one change point covers both commands.

In `GrafanaDeployService.withDashboardTree` (:150-171), the storage mode decides how dashboards travel:
- `HostPath` calls `GrafanaDashboardTreeUploader`, which renders with `pyroscopeNodePortUrl(controlHost.privateIp)` and then runs `RemoteOperationsService.replaceDirectory` over SSH into `/mnt/db1/grafana/dashboards`.
- `RetainedClaim` renders with the in-cluster Pyroscope URL and calls `GrafanaDashboardConfigMaps.mountInto`. That packs the tree into `grafana-dashboard-tree-<n>` maps of up to 512 KiB, with one projected volume per folder (Grafana's file provider does not descend into a symlinked directory). These are applied with the Deployment, and `ConfigHashAnnotator` follows projected sources (`ConfigHashAnnotator.kt:124-131`), so a change rolls Grafana (`Recreate`, `GrafanaManifestBuilder.kt:147`).

Facts this design relies on:
- There are 32 dashboard files. The largest is 294,234 B, about 56% of `DASHBOARD_CONFIGMAP_MAX_BYTES`.
- The provider settings are `updateIntervalSeconds=10`, `disableDeletion=false` and `allowUiUpdates=true` (`GrafanaDashboardProvisioningConfig.kt:64-70`).
- The home dashboard is `GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH=/var/lib/grafana/dashboards/infrastructure/system-overview.json`.
- Only `observability/profiling.json` carries `__PYROSCOPE_URL__`, as a link a browser opens.
- The existing maps are labelled only `app.kubernetes.io/name: grafana`, which `grafana-datasources` and the front ConfigMap share. That label cannot be used to prune.

## Goals / Non-Goals

**Goals**
- No SSH delivers core dashboards, on any environment.
- The folder layout, the UIDs and the home dashboard are unchanged on AWS.
- A removed dashboard loses its ConfigMap and leaves Grafana.
- `DashboardDefaults` is applied on every path.

**Non-Goals**
- The other SSH steps of `up` on AWS.
- Kit dashboards.
- `grafana install`.
- Hot reload without a Grafana restart.

## Decisions

Every decision below is **proposed, pending owner approval**. Each one gives the recommended option first and then the alternatives, as the architect framed them.

### D1. Delivery mechanism: ConfigMaps and projected volumes on every environment (proposed)

The `when (hosting.storage)` branch goes away, and both storage modes take the ConfigMap path. On a host path, the per-folder projected volumes mount under the hostPath data mount at `/var/lib/grafana/dashboards/<folder>`. The claim path already does this, and `PortableObservabilityPlacementIntegrationTest` shows that Grafana files those dashboards into their folders.

- **Recommended: ConfigMaps and projected volumes.**
  - It reuses tested code and keeps the same file provider, so AC4 holds by construction.
  - It needs no new image and no RBAC, and Grafana does not have to be reachable at deploy time.
- **Alternative: the Grafana HTTP API, as kits use it.**
  - The CLI must reach a ready Grafana.
  - Dashboards become editable database rows that nothing reconciles.
  - Deletion becomes a list-and-diff over the API.
  - Folder UIDs come from the API rather than the provider.
  - The home dashboard needs an org preference call.
  - Puts AC4 at risk.
- **Alternative: a k8s-sidecar that watches labelled ConfigMaps and writes into an emptyDir.**
  - Picks up an edit within seconds without a roll.
  - Adds an image, plus a ServiceAccount, Role and RoleBinding to watch ConfigMaps (which matters on EKS), plus folder annotations.
  - Worth it only if "never restart Grafana for a dashboard edit" is a hard requirement.

### D2. Granularity: one ConfigMap per file, and refuse a file over budget (proposed)

- **Recommended: one ConfigMap per file.**
  - The packed scheme folds the tree in catalog order (`GrafanaDashboardConfigMaps.kt:63-76`), so removing one dashboard shifts every later index and rewrites every later map.
  - One map per file gives each dashboard a stable identity for apply and prune, and an edit touches one object.
  - Cost: about 32 apply calls instead of 2-3.
  - A file over 512 KiB fails the deploy, naming the file. Server-side apply writes no `last-applied-configuration`, so the 1 MiB object limit is not under pressure.
- **Alternative: keep the packed maps.** Fewer objects, but the index churn on removal makes pruning and diffs noisy.
- **Alternative: split an oversized file across maps, as the issue allows.** Deferred until a file approaches the limit, because it needs reassembly logic.

### D3. Naming: readable names with a collision check (proposed)

- **Recommended: `grafana-dashboard-<folder>-<stem>`.**
  - Lowercased, and every run of characters outside `[a-z0-9]` becomes `-`, trimmed. For example, `cassandra/ab-comparison.json` becomes `grafana-dashboard-cassandra-ab-comparison`. The longest name today is about 51 characters.
  - Two paths that map to the same name (`a-b/c.json` and `a/b-c.json`) fail at render, naming both.
  - Volume names are `dashboards-<folder>`, checked as a DNS-1123 label.
  - The data key is the file name.
  - Readable in `kubectl get cm`.
- **Alternative: append 8 hex characters of the path's sha256.** Collision-proof without a check, but the names are unreadable.

### D4. How an edit reaches Grafana: the hash rolls Grafana (proposed)

- **Recommended: keep the dashboard maps in the config hash.**
  - When `grafana update-config` or `up` returns, Grafana already shows the change. This is stronger than AC2's "after its provisioning interval".
  - `ConfigChangeReport` stays honest.
  - Cost: a Grafana restart of a few seconds per edit (`Recreate`). Plugins persist under `/var/lib/grafana`, so they are not downloaded again.
- **Alternative: exclude the dashboard maps from the hash.** Kubelet's sync period (about 60-90 s) plus the provider's 10 s interval would pick up content edits without a restart, which matches AC2's wording literally.
  - `ConfigHashAnnotator` would need an exclusion mechanism. It refuses unknown references by design.
  - Adding or removing a file still changes the volume items, so Grafana still rolls.
  - The report would say "unchanged" while content changes.
  - Tests would have to poll.

If D4 is approved as recommended, the delta's edit scenario says the change is served "when `grafana update-config` returns".

### D5. SSH remnants (proposed)

- **(a) `RemoteOperationsService.replaceDirectory`: recommended to delete it** with its implementation and its fakes (`TestModules.kt`, `UpTestFixture.kt`, `FakeNodes.kt`, `InstallFileTest.kt`, `DefaultRemoteOperationsServiceTest.kt`). It has no other production caller. Alternative: keep it for a later use that nothing plans.
- **(b) Pyroscope link: recommended to choose the URL by `hosting.publishing`, in `GrafanaStage`.** Add `pyroscopeBrowserUrl(publishing, controlHost)` to `profiling/ProfilingConfig.kt`:
  - `NodePorts` returns the NodePort on the control node's private IP. A missing control node fails, naming the need.
  - `ClusterIp` returns the Service DNS URL, moved from `GrafanaDeployService`.

  `GrafanaDeployService.deploy` and `createDatasourcesConfigMap` then take no `ClusterHost`. Alternatives:
  - Always use the in-cluster URL. Simpler, but it breaks the AWS "Pyroscope UI" link, because a browser going through SOCKS resolves names on the control node, which does not know `.svc` names.
  - Keep `controlHost` in the deploy signature. Less churn, but it keeps the coupling that #1089 (removing node dependencies from the observability deploy) is removing.

### D6. Events (proposed)

- **Recommended:** replace `Grafana.DashboardTreeUploading(count, path)` and `DashboardTreeUploaded` with one `Grafana.DashboardsRendered(count)`, and emit the existing `K8s.ConfigMapDeleted` for each map pruned. The upload events and their `path` no longer describe anything real.
- **Alternative:** also apply the dashboard maps outside the per-resource loop with one summary event. This removes 32 `ResourceApplying` console lines, at the cost of a second apply path in `deploy`.

### Flow

```
GrafanaStage.deploy(state, hosting)
  pyroscopeUrl = pyroscopeBrowserUrl(hosting.publishing, state.getControlHost())
  -> GrafanaDeployService.deploy(context, bucket, hosting, pyroscopeUrl)
       datasources ConfigMap (unchanged)
       tree = treeWriter.render(pyroscopeUrl, context)     // DashboardDefaults applied here
       emit Grafana.DashboardsRendered(tree.size)
       resources = GrafanaDashboardConfigMaps.mountInto(grafanaResources, tree)  // fails fast on oversize/collision
       ConfigHashAnnotator.annotate -> ConfigChangeReport -> server-side apply each resource
       k8sService.pruneConfigMaps(ns, {easydblab.com/grafana-dashboard: core}, keep = rendered names)
```

Steps run in this order:
1. Apply the dashboard maps.
2. Apply the Deployment, whose new template no longer references any removed map.
3. Prune.

With `Recreate`, the old pod is gone before the new one starts, so the non-optional projected sources always exist when a pod starts.

New interface: `K8sService.pruneConfigMaps(namespace, labels, keep: Set<String>): Result<List<String>>`. It lists the ConfigMaps with `labels` and deletes those not in `keep`, as `deleteConfigMapsByLabels` does (`DefaultK8sStorageOperations.kt:121-141`). A prune failure fails the deploy.

New constants:
- `Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL = "easydblab.com/grafana-dashboard"`, value `core`.
- `DASHBOARD_CONFIGMAP_PREFIX = "grafana-dashboard-"`.

## Risks / Trade-offs

- **R1. AC1 says "no SSH command ran", but `up` on AWS still SSHes** for `OnNodeStateDirectories` and other steps. The spec scopes this to "no SSH command delivers, copies or replaces a dashboard". Off AWS (a brought cluster), `up` runs no SSH at all. The issue text needs the owner to confirm this reading (open question).
- **R2. Grafana restarts on every dashboard edit**, for a few seconds. `up` waits for the rollout. This is accepted under D4.
- **R3. 32 more `ResourceApplying` console lines.** This is noise, not a fault (D6 alternative).
- **R4. Pruning before the Deployment apply** would leave a live template that references a deleted map, and a new pod would hang in ContainerCreating. Mitigation: prune strictly after apply. The removal scenario asserts the order.
- **R5. Old SSH-uploaded files on a host-path cluster first brought up by the old CLI.** A mounted folder hides them, but a folder deleted from the tree would leave them visible. Accepted, because clusters are ephemeral. No migration step.
- **R6. A name collision, or a file name outside `[-._a-zA-Z0-9]`, fails at render.** A future rename then fails the build instead of overwriting silently.
- **R7. A dashboard that grows past 512 KiB fails the deploy**, naming the file. The largest today is at 56%.
- **R8. Spec conflict with the in-flight change `issue-1064`**, which modifies the same requirement. This delta is built on 1064's text, so the order of archiving does not matter. Whichever archives second must keep the other's lines.
- **R9. The Pyroscope link on `ClusterIp` environments stays an in-cluster name**, which a browser cannot open. That is today's behaviour, and it is out of scope.
- **R10. If D4 goes the other way**, `update-config` returns about 60-100 s before Grafana shows an edit.
