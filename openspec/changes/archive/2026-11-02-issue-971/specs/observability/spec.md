## MODIFIED Requirements

### Requirement: Grafana Dashboards

The system MUST provide pre-configured Grafana dashboards for all supported databases and infrastructure. Dashboard titles MUST use simple descriptive names without cluster name prefixes. The Grafana pod SHALL include an image renderer sidecar for server-side panel rendering, and the `aws-sigv4-proxy` and documents web server sidecars that the `test-documents` capability specifies. Dashboard JSON SHALL be loaded directly from classpath resources without template substitution, preserving Grafana built-in variables like `$__rate_interval`. The only change made to a dashboard on its way to Grafana SHALL be the install-time pass that the `multi-cluster-dashboards` capability specifies ("Installed dashboards default to the current cluster"), plus the `__PYROSCOPE_URL__` substitution.

Core dashboards SHALL be delivered as a file tree, not as Kubernetes objects: `grafana update-config` SHALL copy every `dashboards/<folder>/<file>.json` on the classpath onto the control node's Grafana data hostPath (`/mnt/db1/grafana/dashboards`, `/var/lib/grafana/dashboards` inside the pod), and Grafana SHALL be provisioned with exactly one file provider whose `foldersFromFilesStructure` is true and which names no folder, so each directory becomes a Grafana folder of the same title. No code SHALL enumerate dashboards by hand; the set is discovered from the classpath.

Every dashboard that has a `cluster` variable SHALL make it multi-select with an "All" option, except the Tests dashboard, whose `cluster` variable is single-select. All metric dashboards SHALL include an ad hoc filters variable. All metric panel queries SHALL be scoped by `{cluster=~"$cluster"}`. No native ClickHouse datasource SHALL be provisioned.

#### Scenario: Dashboard JSON is not processed by TemplateService

- **WHEN** the dashboard tree is written for upload
- **THEN** each dashboard JSON SHALL be loaded directly from the classpath without passing through `TemplateService.substitute()`
- **AND** all Grafana built-in variables (e.g., `$__rate_interval`, `$__interval`) SHALL be preserved verbatim in the deployed JSON
- **AND** every occurrence of `__PYROSCOPE_URL__` in any dashboard SHALL be replaced with the cluster's Pyroscope URL
- **AND** the install-time pass SHALL be applied; no other change SHALL be made

#### Scenario: Dashboard titles use descriptive names

- **WHEN** the user views the Grafana dashboard list
- **THEN** each dashboard title is a simple descriptive name (e.g., "System Overview", "EMR Overview", "Profiling") without any cluster name prefix

#### Scenario: Renderer container runs alongside Grafana

- **WHEN** the Grafana deployment is applied to the cluster
- **THEN** the pod SHALL contain a `grafana-image-renderer` container using the `grafana/grafana-image-renderer` image pinned to release v5.12.4, never `latest`
- **AND** the renderer SHALL listen on port 8081

#### Scenario: Grafana is configured to use the renderer

- **WHEN** the Grafana deployment is applied to the cluster
- **THEN** the `GF_RENDERING_SERVER_URL` env var SHALL point to `http://localhost:8081/render`
- **AND** the `GF_RENDERING_CALLBACK_URL` env var SHALL point to `http://localhost:3000/`

#### Scenario: Documents sidecars run alongside Grafana

- **WHEN** the Grafana deployment is applied to the cluster
- **THEN** the pod SHALL contain the `aws-sigv4-proxy` container and the documents web server container
- **AND** Grafana SHALL run with `disable_sanitize_html` set to true

#### Scenario: Cluster variables read the metrics picker

- **WHEN** any dashboard that has a `cluster` variable is deployed via `grafana update-config`
- **THEN** the variable SHALL query `label_values(up, cluster)` against `${metrics_datasource}`, never a fixed datasource uid, except on the Tests dashboard, whose variable lists the clusters with `up` samples within `lookback` against `${metrics_datasource}`
- **AND** the variable SHALL have `multi: true` and `includeAll: true`, except on the Tests dashboard

#### Scenario: All metric panels are cluster-scoped

- **WHEN** a metric panel renders its query
- **THEN** the PromQL expression SHALL include a `cluster=~"$cluster"` label selector

#### Scenario: No native ClickHouse datasource is provisioned

- **WHEN** Grafana loads its datasource configuration
- **THEN** no datasource of type `grafana-clickhouse-datasource` SHALL be present

#### Scenario: Cluster comparison dashboard appears in Grafana

- **WHEN** `grafana update-config` is run
- **THEN** `dashboards/cassandra/cluster-comparison.json` SHALL be copied to `/mnt/db1/grafana/dashboards/cassandra/cluster-comparison.json` on the control node
- **AND** the single file provider SHALL file it into the `cassandra` folder from its directory name
- **AND** no ConfigMap, volume, or volume mount SHALL exist for it

#### Scenario: Copied tree replaces the previous one

- **WHEN** `grafana update-config` is run against a cluster that already has a dashboard tree
- **THEN** the new tree SHALL be staged on the same filesystem as `/mnt/db1/grafana/dashboards` and renamed into place, so the provider never reads an empty or partial directory
- **AND** the previous tree SHALL be removed once the new one is in place, so a dashboard deleted from the repo disappears from Grafana
- **AND** the tree SHALL be owned by the Grafana user (uid 472)

#### Scenario: Same file name in two folders

- **WHEN** `dashboards/<a>/<name>.json` and `dashboards/<b>/<name>.json` both exist
- **THEN** both SHALL be deployed, each in its own folder

#### Scenario: System Overview is grouped by purpose

- **WHEN** a user opens System Overview
- **THEN** panels are grouped into collapsible rows named CPU, Memory, Disk, Network, and Processes, by what the metric explains, not by which collector produced it
- **AND** each row lays panels out two per line at half width, one metric per panel

#### Scenario: Disk and network panels exclude virtual devices

- **WHEN** a Disk panel queries per-device metrics
- **THEN** loop devices and partitions (`device!~"loop.*|.*p[0-9]+"`) are excluded
- **AND** Network panels match only `ens.*` and `eth.*` interfaces
