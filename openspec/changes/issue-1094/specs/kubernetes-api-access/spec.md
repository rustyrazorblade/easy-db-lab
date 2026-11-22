## ADDED Requirements

### Requirement: Every object the CLI applies carries the ownership labels

Every object the CLI applies through the Kubernetes API, namespaced or cluster-scoped, SHALL carry `app.kubernetes.io/managed-by=easy-db-lab` and `easydblab/cluster=<clusterId>` in its top-level `metadata.labels`. The labels SHALL be added in one place on the apply path, so the stack deploy, the Grafana deploy, the collector resync, the sidecar, stress jobs and `cluster-config` all get them. They SHALL NOT be added to a `spec.selector` or to a pod template. Kit PersistentVolumes and the namespace the CLI creates SHALL carry the same labels. Labels the object already has SHALL be kept.

#### Scenario: Applied object is labelled

- **WHEN** the CLI applies a ConfigMap and a ClusterRole for cluster `<clusterId>`
- **THEN** both live objects carry `app.kubernetes.io/managed-by=easy-db-lab` and `easydblab/cluster=<clusterId>`

#### Scenario: Selector and pod template unchanged

- **WHEN** the CLI applies a Deployment
- **THEN** the live Deployment's `spec.selector` and pod template labels are exactly those its builder rendered
- **AND** applying it again does not change its pod-template hash, so Kubernetes does not roll it

#### Scenario: Kit PV is labelled

- **WHEN** a `kit-storage` step creates a local PersistentVolume for a kit
- **THEN** the PV carries both labels

#### Scenario: Existing labels kept

- **WHEN** the CLI applies an object whose builder set `app.kubernetes.io/name=grafana`
- **THEN** the live object carries that label as well as the two ownership labels

### Requirement: Cluster-scoped objects are named with the cluster id

Every cluster-scoped object the CLI creates (for example the ClusterRoles and ClusterRoleBindings of the OTel collector, the host collector, Pyroscope eBPF and kube-state-metrics) SHALL be named `<base>-<clusterId>`, with the full cluster id, in every environment. A ClusterRoleBinding's `roleRef` SHALL name the suffixed ClusterRole, and its subject SHALL name the cluster namespace.

#### Scenario: ClusterRole named with the cluster id

- **WHEN** the stack is rendered for cluster `<clusterId>`
- **THEN** the collector's ClusterRole is named `otel-collector-<clusterId>`, and every cluster-scoped object's name ends in `-<clusterId>`

#### Scenario: Binding points at the suffixed role

- **WHEN** the stack is rendered for cluster `<clusterId>` in namespace `lab-a`
- **THEN** each ClusterRoleBinding's `roleRef` names a ClusterRole ending in `-<clusterId>`, and its ServiceAccount subject is in `lab-a`
