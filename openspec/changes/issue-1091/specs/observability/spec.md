## ADDED Requirements

### Requirement: Observability backends and Grafana run on the pod network behind ClusterIP Services

Mimir, Loki, Tempo, Pyroscope and Grafana SHALL run as ordinary pod-network workloads behind ClusterIP Services, with no `hostNetwork`. Their placement SHALL come from a resolved `Placement` value — a node selector, tolerations, and a host-network flag — derived once from the recorded environment, never from testing an environment name. On AWS the resolved placement SHALL keep today's control-node selector and its matching toleration; where the environment declares no such node the selector and tolerations SHALL be empty. An `init` user node label SHALL merge into the selector. The same manifests SHALL be used on every environment.

On an environment that owns its nodes (AWS), each of these workloads SHALL also publish its client ports on the node as container `hostPort`s, with no `hostNetwork`. Callers outside the pod network reach those ports at the control node's private IP: redirected clusters, node-side agents, the documents iframe, and the CLI and the teardown flush until #1090. The ports are Mimir 9009, Loki 3100, Tempo 3200, 4320 and 4321, Pyroscope 4040, Grafana 3000 and the documents web server 3080. Where the environment does not own its nodes, no workload SHALL declare a host port.

Every in-cluster caller of these backends SHALL address them by Service DNS (`<service>.<namespace>.svc`), not `localhost` or a node IP. This SHALL include the Grafana datasources' backend base URLs and the OTel collector's backend exporter endpoints.

Each backend's local state SHALL be stored on a `hostPath` where the environment provides `HOST_PATH` (keeping today's `/mnt/db1/<backend>` on AWS), and otherwise on a per-backend claim whose StorageClass reclaims with `Retain`; `emptyDir` SHALL NOT be used for backend state. Each backend workload SHALL expose a `serviceAccountName` and an optional store-credentials Secret mount as plug points for pod credentials; on AWS neither alters today's instance-role behaviour.

#### Scenario: Backends reach Ready without a control-plane node
- **GIVEN** a cluster where no node carries `node-role.kubernetes.io/control-plane=true`
- **WHEN** the observability stack deploys with an empty placement
- **THEN** Mimir, Loki, Tempo, Pyroscope and Grafana each reach Ready
- **AND** none of them runs with `hostNetwork`

#### Scenario: AWS placement and storage are unchanged
- **GIVEN** an AWS environment
- **WHEN** the five workloads are rendered
- **THEN** each carries the `node-role.kubernetes.io/control-plane=true` selector and its matching toleration
- **AND** each mounts its state from the `/mnt/db1/<backend>` host path, exactly as today

#### Scenario: On AWS the client ports stay published on the control node
- **GIVEN** an AWS environment
- **WHEN** the five workloads are rendered
- **THEN** each declares exactly its client ports as host ports, and none runs with `hostNetwork`

#### Scenario: Off owned nodes no workload declares a host port
- **GIVEN** an environment that does not own its nodes
- **WHEN** the five workloads are rendered
- **THEN** none declares a host port

#### Scenario: Grafana datasources use Service DNS
- **WHEN** Grafana's datasources are built
- **THEN** each backend datasource URL is that backend's Service DNS name, not `localhost` or a node IP

#### Scenario: The collector reaches the backends by Service DNS
- **GIVEN** the backends run behind ClusterIP Services with no node-IP listener
- **WHEN** the OTel collector exports metrics, logs and traces
- **THEN** it addresses Mimir, Loki and Tempo by Service DNS
- **AND** the dashboards show data

#### Scenario: A rescheduled backend stays reachable
- **WHEN** a backend Pod is rescheduled to another node in the volume's zone
- **THEN** Grafana and the collector still reach it through its ClusterIP Service

#### Scenario: Off owned nodes, backend state is on a retained claim
- **GIVEN** an environment without `HOST_PATH`
- **WHEN** a backend is rendered
- **THEN** its state is on a per-backend claim whose StorageClass reclaims with `Retain`
- **AND** no backend uses `emptyDir` for its state

## MODIFIED Requirements

### Requirement: kube-state-metrics reports Kubernetes object state
The system SHALL deploy kube-state-metrics with the core observability stack on every cluster regardless of CNI and telemetry mode, as a single-replica Deployment in the pod network, with a ServiceAccount, a read-only ClusterRole (every rule grants only `list` and `watch`), a ClusterRoleBinding, and a ClusterIP Service on port 8080. Its placement SHALL come from the same resolved `Placement` value as the observability backends, so on AWS it stays on the control node and elsewhere it is scheduled by the resolved selector. The image tag SHALL be pinned in `Constants.KubeStateMetrics.IMAGE`. The OTel collector SHALL scrape it through Kubernetes pod discovery filtered to the collector's own node, so exactly one collector scrapes it and `instance` is the pod name.

#### Scenario: Deployed on every cluster
- **WHEN** `easy-db-lab up` deploys the observability stack, in local or redirect mode, on Flannel or Cilium
- **THEN** the `kube-state-metrics` ServiceAccount, ClusterRole, ClusterRoleBinding, Service, and Deployment exist in the namespace
- **AND** on AWS the Deployment's pod is scheduled on the control node

#### Scenario: Placement follows the environment
- **GIVEN** a cluster with no control-plane node
- **WHEN** kube-state-metrics is rendered with an empty placement
- **THEN** its pod carries no control-plane selector or toleration and is schedulable on any node

#### Scenario: Pod state metrics reach Mimir
- **GIVEN** a cluster that is up
- **WHEN** a pod is running
- **THEN** `kube_pod_status_phase` for that pod is queryable in Mimir with the cluster label

#### Scenario: RBAC is read-only
- **WHEN** the `kube-state-metrics` ClusterRole is applied
- **THEN** no rule grants a verb other than `list` or `watch`
