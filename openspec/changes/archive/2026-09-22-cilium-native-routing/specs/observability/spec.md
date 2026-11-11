## ADDED Requirements

### Requirement: kube-state-metrics reports Kubernetes object state
The system SHALL deploy kube-state-metrics with the core observability stack on every cluster regardless of CNI and telemetry mode, as a single-replica Deployment on the control node in the pod network, with a ServiceAccount, a read-only ClusterRole (every rule grants only `list` and `watch`), a ClusterRoleBinding, and a ClusterIP Service on port 8080. The image tag SHALL be pinned in `Constants.KubeStateMetrics.IMAGE`. The OTel collector SHALL scrape it through Kubernetes pod discovery filtered to the collector's own node, so exactly one collector scrapes it and `instance` is the pod name.

#### Scenario: Deployed on every cluster
- **WHEN** `easy-db-lab up` deploys the observability stack, in local or redirect mode, on Flannel or Cilium
- **THEN** the `kube-state-metrics` ServiceAccount, ClusterRole, ClusterRoleBinding, Service, and Deployment exist in the `default` namespace
- **AND** the Deployment's pod is scheduled on the control node

#### Scenario: Pod state metrics reach VictoriaMetrics
- **GIVEN** a cluster that is up
- **WHEN** a pod is running
- **THEN** `kube_pod_status_phase` for that pod is queryable in VictoriaMetrics with the cluster label

#### Scenario: RBAC is read-only
- **WHEN** the `kube-state-metrics` ClusterRole is applied
- **THEN** no rule grants a verb other than `list` or `watch`
