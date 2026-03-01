### Requirement: OTel Collector adds node_role from K8s node labels

The OTel Collector on cluster nodes SHALL use the k8sattributes processor to extract the K8s node label `type` and set it as the `node_role` resource attribute on all locally-collected metrics and logs.

#### Scenario: Cassandra node metrics include node_role=db

- **WHEN** the OTel Collector is running on a Cassandra node with K8s label `type=db`
- **THEN** all metrics in the `metrics/local` pipeline SHALL have `node_role=db` as a resource attribute
- **AND** the attribute is converted to the `node_role` metric label by the prometheusremotewrite exporter

#### Scenario: Stress node metrics include node_role=app

- **WHEN** the OTel Collector is running on a stress node with K8s label `type=app`
- **THEN** all metrics in the `metrics/local` pipeline SHALL have `node_role=app` as a resource attribute

#### Scenario: Control node metrics include node_role=control

- **WHEN** the OTel Collector is running on the control node with K8s label `type=control`
- **THEN** all metrics in the `metrics/local` pipeline SHALL have `node_role=control` as a resource attribute

#### Scenario: Logs include node_role

- **WHEN** the OTel Collector is running on any cluster node
- **THEN** all logs in the `logs/local` pipeline SHALL have `node_role` set to the node's type label value

#### Scenario: OTLP pipelines are not affected

- **WHEN** metrics or logs arrive via the OTLP receiver from remote nodes (e.g., Spark)
- **THEN** the k8sattributes processor SHALL NOT be applied to those pipelines
- **AND** existing `node_role` attributes from remote sources are preserved unchanged

### Requirement: OTel Collector has RBAC for K8s API access

The OTel Collector DaemonSet SHALL run with a ServiceAccount that has read access to K8s pods and nodes, required by the k8sattributes processor.

#### Scenario: RBAC resources are created

- **WHEN** OTel Collector K8s resources are applied
- **THEN** a ServiceAccount, ClusterRole, and ClusterRoleBinding SHALL be created
- **AND** the ClusterRole SHALL grant `get`, `watch`, `list` on `pods` and `nodes`
- **AND** the DaemonSet SHALL reference the ServiceAccount

### Requirement: Control node receives type label during cluster setup

The control node SHALL be labeled with `type=control` in K8s during the cluster `up` command, since it is the K3s server and does not receive labels via K3s agent configuration.

#### Scenario: Control node labeled during Up

- **WHEN** the `up` command reaches the node labeling phase
- **THEN** the control node SHALL be labeled with `type=control` via `k8sService.labelNode()`
- **AND** this occurs before OTel and Grafana resources are deployed
