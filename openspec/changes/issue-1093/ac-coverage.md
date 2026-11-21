| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC1 | `up` on any environment: every core dashboard is in Grafana in its folder, with the pickers and cluster defaults set, and no SSH command ran | `Every core dashboard is delivered without SSH`; `On any storage the dashboards reach Grafana through the K8s API`; `Dashboard JSON is not processed by TemplateService` (install-time pass) | ✅ Covered. "No SSH" is scoped to dashboard delivery, because `up` on AWS still SSHes for other steps (R1, open question) |
| AC2 | A JSON change plus `grafana update-config`: Grafana shows the change after its provisioning interval | `A dashboard edit reaches Grafana` | ✅ Covered, more strongly: the change shows when the command returns (D4, pending) |
| AC3 | A dashboard removed from the tree: `grafana update-config` removes its ConfigMap | `A dashboard removed from the tree loses its ConfigMap` | ✅ Covered |
| AC4 | `up` on AWS: folder layout and UIDs unchanged | `Folder layout and UIDs are unchanged on AWS`; `Cluster comparison dashboard appears in Grafana`; `Same file name in two folders` | ✅ Covered |
| Scope | `DashboardDefaults` still runs first | `Every core dashboard is delivered without SSH` (pickers and cluster set); `Dashboard JSON is not processed by TemplateService` | ✅ Covered |
| Scope | Kit dashboards keep their path; `grafana install` is out | Requirement text ("Kit dashboards and `grafana install` keep their Grafana HTTP API path") | ⚠️ Excluded as a scenario. These paths are unchanged, and their existing specs cover them |
| Scope | One ConfigMap per file; split only if over the limit | Requirement text; `A dashboard that cannot be delivered fails before anything is applied` | ✅ Covered. Splitting is deferred (D2) |
| Risk R1 | `up` on AWS still uses SSH for other steps | `Every core dashboard is delivered without SSH` (worded for dashboards) | ⚠️ Excluded. The other SSH steps belong to other issues of epic #1032 |
| Risk R2 | Grafana restarts per edit | `A dashboard edit reaches Grafana` (roll asserted) | ✅ Covered |
| Risk R3 | Extra console lines | — | ⚠️ Excluded. Cosmetic; D6 alternative |
| Risk R4 | Prune before the Deployment apply hangs a new pod | `A dashboard removed from the tree loses its ConfigMap` ("after the Grafana Deployment … is applied") | ✅ Covered |
| Risk R5 | Old SSH-uploaded files under a removed folder on a host-path cluster | — | ⚠️ Excluded. Clusters are ephemeral and new clusters never have the old files |
| Risk R6 | Name collision or invalid key | `A dashboard that cannot be delivered fails before anything is applied` | ✅ Covered |
| Risk R7 | A dashboard over 512 KiB | `A dashboard that cannot be delivered fails before anything is applied` | ✅ Covered |
| Risk R8 | Spec conflict with `issue-1064` | — | ⚠️ Excluded as a scenario. Handled by basing the delta on 1064's text (overrides.md) |
| Risk R9 | Pyroscope link on `ClusterIp` is in-cluster only | `The Pyroscope link follows how the environment publishes services` | ✅ Covered (today's behaviour, stated) |
| Risk R10 | Late pickup if D4 is reversed | `A dashboard edit reaches Grafana` | ✅ Covered under D4 as proposed |
| Risk | Prune must not touch other Grafana ConfigMaps | `A dashboard removed from the tree loses its ConfigMap` (unlabelled maps kept) | ✅ Covered |
