## Overrides existing behavior

### observability: Grafana Dashboards

**Currently:** Core dashboards "SHALL be delivered as a file tree, not as Kubernetes objects". `grafana update-config` copies them onto `/mnt/db1/grafana/dashboards` on the control node. The scenario "Cluster comparison dashboard appears in Grafana" says "no ConfigMap, volume, or volume mount SHALL exist for it". The scenario "Copied tree replaces the previous one" stages and renames the tree as uid 472.

**This change:** Core dashboards are ConfigMaps on every environment, one per file, labelled `easydblab.com/grafana-dashboard: core`. They are projected per folder at `/var/lib/grafana/dashboards/<folder>`, rolled by the config hash, and pruned by label after apply. No SSH delivers them. The cluster comparison scenario names its ConfigMap. The copy scenario is replaced by:
- "Every core dashboard is delivered without SSH"
- "Folder layout and UIDs are unchanged on AWS"
- "A dashboard edit reaches Grafana"
- "A dashboard removed from the tree loses its ConfigMap"
- "A dashboard that cannot be delivered fails before anything is applied"
- "The Pyroscope link follows how the environment publishes services"

### observability: Observability backends and Grafana run on the pod network behind Services, published as NodePorts on owned nodes

**Currently:** The scenario "On a claim the dashboards reach Grafana through the K8s API" applies only to an environment without `HOST_PATH`.

**This change:** It is renamed "On any storage the dashboards reach Grafana through the K8s API" and covers host-path storage too. The rest of the requirement is unchanged.

## Conflicts with other in-flight changes

- **`issue-1064` (primary ENI by MAC):** it also MODIFIES `observability: Grafana Dashboards`, changing only the network-panel line of "Disk and network panels exclude virtual devices" to `en.*` and `eth.*`. This delta is written on 1064's text, so it already carries that line. Whichever change archives second must keep both edits.
- No other open change modifies the `observability` capability.
