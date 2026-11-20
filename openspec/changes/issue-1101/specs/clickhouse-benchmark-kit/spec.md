## MODIFIED Requirements

### Requirement: App node placement
`prepare` and `start` SHALL call `edl_require_app_node clickhouse-benchmark` before they create any pod or ConfigMap, and SHALL create every pod with `edl_pod_overrides`, so the pod carries the app role's node selector (`APP_NODE_SELECTOR`): `type=app` on the nodes easy-db-lab builds, the `init --role-nodes app=...` mapping, or none when the app role is unconstrained.

#### Scenario: Pod on an app node
- **WHEN** `prepare` or `start` runs on an AWS cluster with an app node
- **THEN** the pod is scheduled on a node labelled `type=app`

#### Scenario: Pod anywhere when the app role is unconstrained
- **WHEN** `prepare` or `start` runs with `APP_NODE_SELECTOR` `{}` on a cluster with one unlabelled node
- **THEN** the pod carries an empty `nodeSelector` and is scheduled on that node

#### Scenario: No app node
- **WHEN** `prepare` or `start` runs on a cluster where no node matches `APP_NODE_LABELS`
- **THEN** it fails before it creates a pod, with `clickhouse-benchmark needs an app node (<APP_NODE_LABELS>); this cluster has none`
