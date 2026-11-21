## MODIFIED Requirements

### Requirement: Grafana Dashboards

The system MUST provide pre-configured Grafana dashboards for all supported databases and infrastructure. Dashboard titles MUST use simple descriptive names without cluster name prefixes. The Grafana pod SHALL include an image renderer sidecar for server-side panel rendering, and the `aws-sigv4-proxy` and documents web server sidecars that the `test-documents` capability specifies. Dashboard JSON SHALL be loaded directly from classpath resources without template substitution, preserving Grafana built-in variables like `$__rate_interval`. The only change made to a dashboard on its way to Grafana SHALL be the install-time pass that the `multi-cluster-dashboards` capability specifies ("Installed dashboards default to the current cluster"), plus the `__PYROSCOPE_URL__` substitution.

Core dashboards SHALL reach Grafana through the Kubernetes API only, on every environment, and no SSH command SHALL deliver, copy or replace a dashboard. Each `dashboards/<folder>/<file>.json` on the classpath SHALL be rendered (the install-time pass and the `__PYROSCOPE_URL__` substitution applied) into its own ConfigMap named `grafana-dashboard-<folder>-<stem>` (lowercased; every run of characters outside `[a-z0-9]` becomes `-`), holding one key, the file name, and labelled `app.kubernetes.io/name: grafana` and `easydblab.com/grafana-dashboard: core`. The Grafana Deployment SHALL mount one projected volume per folder, read-only, at `/var/lib/grafana/dashboards/<folder>`, and Grafana SHALL be provisioned with exactly one file provider whose `foldersFromFilesStructure` is true and which names no folder, so each directory becomes a Grafana folder of the same title. `up` and `grafana update-config` SHALL apply these ConfigMaps with server-side apply, together with the Grafana resources, and the Grafana config hash SHALL cover them, so a dashboard change rolls Grafana. After the Grafana Deployment is applied, every ConfigMap carrying `easydblab.com/grafana-dashboard: core` that is not in the rendered set SHALL be deleted. A dashboard larger than `Constants.Grafana.DASHBOARD_CONFIGMAP_MAX_BYTES`, two paths that map to the same ConfigMap name, or a folder name that is not a valid volume name SHALL fail the deploy before anything is applied, naming the file or files. No code SHALL enumerate dashboards by hand; the set is discovered from the classpath. Kit dashboards and `grafana install` keep their Grafana HTTP API path.

The "Pyroscope UI" link (`__PYROSCOPE_URL__`) SHALL be the address a browser uses: the control node's private IP and the Pyroscope NodePort where the environment publishes NodePorts, and the Pyroscope Service DNS name otherwise.

Every dashboard that has a `cluster` variable SHALL make it multi-select with an "All" option, except the Tests dashboard, whose `cluster` variable is single-select. All metric dashboards SHALL include an ad hoc filters variable. All metric panel queries SHALL be scoped by `{cluster=~"$cluster"}`. No native ClickHouse datasource SHALL be provisioned.

#### Scenario: Dashboard JSON is not processed by TemplateService

- **WHEN** the dashboards are rendered for delivery
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
- **THEN** `dashboards/cassandra/cluster-comparison.json` SHALL be applied as the ConfigMap `grafana-dashboard-cassandra-cluster-comparison`, key `cluster-comparison.json`
- **AND** the Grafana container SHALL see it at `/var/lib/grafana/dashboards/cassandra/cluster-comparison.json`
- **AND** the single file provider SHALL file it into the `cassandra` folder from its directory name

#### Scenario: Every core dashboard is delivered without SSH

- **GIVEN** any environment, including one with no SSH access to any node (a cluster brought with `init --kubeconfig`)
- **WHEN** `up` deploys the observability stack
- **THEN** every core dashboard SHALL be in Grafana, in the folder named for its directory, with its pickers and cluster variables set by the install-time pass
- **AND** no SSH command SHALL deliver, copy or replace any dashboard file

#### Scenario: Folder layout and UIDs are unchanged on AWS

- **GIVEN** an AWS environment, where Grafana's state is on the `/mnt/db1/grafana` host path
- **WHEN** `up` deploys the observability stack
- **THEN** Grafana SHALL hold the same folders, each with the same dashboards under the same UIDs, as the file tree on the classpath describes
- **AND** the home dashboard SHALL be `infrastructure/system-overview.json`

#### Scenario: A dashboard edit reaches Grafana

- **GIVEN** a running cluster whose Grafana shows a core dashboard
- **WHEN** that dashboard's JSON changes and `grafana update-config` runs
- **THEN** its ConfigMap SHALL hold the new JSON and Grafana SHALL roll because its config hash changed
- **AND** when `grafana update-config` returns, Grafana SHALL serve the new version of the dashboard

#### Scenario: A dashboard removed from the tree loses its ConfigMap

- **GIVEN** a running cluster with a core dashboard ConfigMap for a file that is no longer on the classpath
- **WHEN** `grafana update-config` runs
- **THEN** that ConfigMap SHALL be deleted, after the Grafana Deployment that no longer mounts it is applied
- **AND** the dashboard SHALL no longer be in Grafana
- **AND** every other dashboard ConfigMap SHALL keep its name
- **AND** no ConfigMap without the `easydblab.com/grafana-dashboard: core` label SHALL be deleted

#### Scenario: A dashboard that cannot be delivered fails before anything is applied

- **WHEN** a dashboard is larger than one ConfigMap's budget, or two dashboard paths map to the same ConfigMap name
- **THEN** the deploy SHALL fail before any resource is applied, naming the file or files

#### Scenario: The Pyroscope link follows how the environment publishes services

- **WHEN** the dashboards are rendered on an environment that publishes NodePorts
- **THEN** `__PYROSCOPE_URL__` SHALL be replaced with the control node's private IP and the Pyroscope NodePort
- **AND** on an environment that does not, it SHALL be replaced with the Pyroscope Service DNS name

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
- **AND** Network panels match only `en.*` and `eth.*` interfaces, so the primary ENI shows under any name the instance family gives it (`ens5`, `ens52`, `enp39s0`)

### Requirement: Observability backends and Grafana run on the pod network behind Services, published as NodePorts on owned nodes

Mimir, Loki, Tempo, Pyroscope and Grafana SHALL run as ordinary pod-network workloads behind Services, with no `hostNetwork`. Their placement SHALL come from a resolved `Placement` value — a node selector and tolerations — derived once from the recorded environment, never from testing an environment name. On AWS the resolved placement SHALL be the control-node selector and its matching toleration; where the environment declares no such node the selector and tolerations SHALL be empty. An `init` user node label SHALL merge into the selector. The same container specs SHALL be used on every environment.

No backend or Grafana container SHALL declare a `hostPort` in any environment. Callers outside the pod network SHALL reach them through the published NodePorts defined in the networking specification, never through a container host port.

Every in-cluster caller of these backends SHALL address them by Service DNS (`<service>.<namespace>.svc`), not `localhost` or a node IP, using one shared source of backend addresses. This SHALL include the Grafana datasources' backend base URLs and the OTel collector's backend exporter endpoints.

The `cluster-config` ConfigMap SHALL NOT carry a `control_node_ip` key, and the observability deploy and the collector sync SHALL NOT require a control node in order to publish one.

Each backend's local state SHALL be stored on a `hostPath` where the environment provides `HOST_PATH` (`/mnt/db1/<backend>` on AWS), and otherwise on a per-backend claim whose StorageClass reclaims with `Retain`; `emptyDir` SHALL NOT be used for backend state. Each backend workload SHALL expose a `serviceAccountName` and an optional store-credentials Secret mount as plug points for pod credentials; on AWS the backends authenticate with the instance role.

#### Scenario: Backends reach Ready without a control-plane node
- **GIVEN** a cluster where no node carries `node-role.kubernetes.io/control-plane=true`
- **WHEN** the observability stack deploys with an empty placement
- **THEN** Mimir, Loki, Tempo, Pyroscope and Grafana each reach Ready
- **AND** none of them runs with `hostNetwork`

#### Scenario: AWS placement and storage are unchanged
- **GIVEN** an AWS environment
- **WHEN** the five workloads are rendered
- **THEN** each carries the `node-role.kubernetes.io/control-plane=true` selector and its matching toleration
- **AND** each mounts its state from the `/mnt/db1/<backend>` host path

#### Scenario: A node label no node carries is refused
- **GIVEN** an `init --node-label` that no node carries together with the environment's own selector
- **WHEN** the observability stack deploys
- **THEN** it fails before applying anything, naming the merged selector and that the label must already be on a node

#### Scenario: On AWS no backend or Grafana container declares a host port
- **GIVEN** an AWS environment
- **WHEN** the observability stack is rendered and deployed
- **THEN** no container of Mimir, Loki, Tempo, Pyroscope or Grafana (including the nginx front) declares a `hostPort`
- **AND** none runs with `hostNetwork`

#### Scenario: Off owned nodes no workload declares a host port
- **GIVEN** an environment that does not own its nodes
- **WHEN** the five workloads are rendered
- **THEN** none declares a host port

#### Scenario: Grafana datasources use Service DNS
- **WHEN** Grafana's datasources are built
- **THEN** each backend datasource URL is that backend's Service DNS name, not `localhost` or a node IP

#### Scenario: The collector reaches the backends by Service DNS
- **GIVEN** the backends run behind Services with no node-IP listener
- **WHEN** the OTel collector exports metrics, logs and traces
- **THEN** it addresses Mimir, Loki and Tempo by Service DNS
- **AND** the dashboards show data

#### Scenario: A replaced backend on a retained claim stays reachable
- **GIVEN** an environment without `HOST_PATH`, so the backend's state is on its retained claim
- **WHEN** the backend's pod is deleted and its replacement reattaches the same claim (on another node only within the volume's zone, since the claim is zonal)
- **THEN** Grafana reaches the replacement through the backend's Service, at the same URL its datasource uses

#### Scenario: The stack and the collector sync need no control node IP
- **GIVEN** any environment
- **WHEN** the observability stack deploys, or a kit start or stop resyncs the collector
- **THEN** the `cluster-config` ConfigMap carries no `control_node_ip` key
- **AND** the collector configuration has no endpoint at a node IP
- **AND** the callers outside the cluster that need a node address, EMR's Spark Pyroscope agent address and the server's `accessInfo` URLs, read the control node's private IP from the cluster state on the caller's side, not from `cluster-config`

#### Scenario: Off owned nodes, backend state is on a retained claim
- **GIVEN** an environment without `HOST_PATH`
- **WHEN** a backend is rendered
- **THEN** its state is on a per-backend claim whose StorageClass reclaims with `Retain`
- **AND** no backend uses `emptyDir` for its state

#### Scenario: On any storage the dashboards reach Grafana through the K8s API
- **GIVEN** any environment, whether Grafana's state is on the `HOST_PATH` host path or on its retained claim
- **WHEN** the observability stack deploys
- **THEN** the dashboard tree is applied as ConfigMaps the Grafana Deployment mounts at its dashboard path, with no copy to a node
- **AND** each dashboard is filed in the folder named for its directory

#### Scenario: A claim StorageClass that deletes its volumes is refused
- **GIVEN** an environment without `HOST_PATH` whose backend StorageClass reclaims with `Delete`, sets no reclaim policy, or does not exist
- **WHEN** the observability stack deploys
- **THEN** it fails before applying anything, naming the StorageClass and that it must reclaim with `Retain`

