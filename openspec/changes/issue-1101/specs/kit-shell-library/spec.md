## MODIFIED Requirements

### Requirement: App-node check and pod placement
`edl_require_app_node <kit>` SHALL run `kubectl get nodes -l "${APP_NODE_LABELS}" -o name` when `APP_NODE_LABELS` is set, and `kubectl get nodes -o name` (any schedulable node) when it is empty. When no node matches, it SHALL print `<kit> needs an app node (<selector>); this cluster has none`, where `<selector>` is `APP_NODE_LABELS` or `any node`, and exit non-zero.  `edl_pod_overrides [<configmap> <mount-path> <container>]` SHALL print literal JSON, with no `jq`, for `kubectl run --overrides=<json> --override-type=strategic`, that sets `nodeSelector` to `APP_NODE_SELECTOR` (a JSON map, `{}` when unconstrained) and, when a ConfigMap is given, a volume and a volume mount for it.  It SHALL reject a ConfigMap or container name that is not a DNS-1123 name. The library SHALL NOT name `type=app` or any other fixed node label.

#### Scenario: Cluster has no app node
- **WHEN** `edl_require_app_node clickhouse-benchmark` runs with `APP_NODE_LABELS=type=app` and `kubectl get nodes -l type=app` returns nothing
- **THEN** it prints `clickhouse-benchmark needs an app node (type=app); this cluster has none` and exits non-zero

#### Scenario: Cluster has an app node
- **WHEN** `kubectl get nodes -l "${APP_NODE_LABELS}"` returns one node
- **THEN** `edl_require_app_node` exits 0

#### Scenario: Unconstrained app role accepts any node
- **WHEN** `APP_NODE_LABELS` is empty and the cluster has one schedulable node
- **THEN** `edl_require_app_node` exits 0

#### Scenario: Overrides JSON uses the app selector
- **WHEN** `edl_pod_overrides edl-q-1 /edl run` runs with `APP_NODE_SELECTOR={"type":"app"}`
- **THEN** its output is valid JSON with `spec.nodeSelector.type` equal to `app`, one ConfigMap volume for `edl-q-1` and a mount at `/edl` on container `run`

#### Scenario: Overrides JSON with an unconstrained app role
- **WHEN** `edl_pod_overrides` runs with `APP_NODE_SELECTOR={}` under bash 3.2 and `set -u`
- **THEN** its output is valid JSON with an empty `spec.nodeSelector`

#### Scenario: Bad name rejected
- **WHEN** `edl_pod_overrides 'Bad_Name' /edl run` runs
- **THEN** it exits non-zero and names `Bad_Name`
