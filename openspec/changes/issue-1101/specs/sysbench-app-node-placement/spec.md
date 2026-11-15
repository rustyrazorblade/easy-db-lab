## MODIFIED Requirements

### Requirement: sysbench pods run on app nodes
Every pod that the `sysbench` kit creates (the run pod, the prepare pod, the createdb pod and the cleanup pod) SHALL carry the app role's node selector (`APP_NODE_SELECTOR`), set with `edl_pod_overrides` from the shared kit shell library.  On AWS this is `type=app`; where the app role is unconstrained it is empty.  The `sysbench` scripts SHALL source the library through `EDL_KIT_LIB`.

#### Scenario: Run pod on an app node
- **WHEN** `sysbench-<target> start` runs on an AWS cluster with an app node
- **THEN** the sysbench run pod is created with `nodeSelector` `type=app`
- **AND** it is scheduled on a node labelled `type=app`

#### Scenario: Prepare pods on an app node
- **WHEN** `sysbench-<target> prepare` runs on an AWS cluster with an app node
- **THEN** the createdb pod and the prepare pod are created with `nodeSelector` `type=app`

#### Scenario: Cleanup pod on an app node
- **WHEN** `sysbench-<target> stop` runs on an AWS cluster with an app node
- **THEN** the cleanup pod is created with `nodeSelector` `type=app`

#### Scenario: Run pod on the app node of a real cluster
- **WHEN** `sysbench` runs on a cluster with 1 app node and 3 db nodes
- **THEN** its run pod is on the app node

#### Scenario: Run pod on a one-node cluster
- **WHEN** `sysbench` runs on a one-node cluster with an unconstrained app role
- **THEN** its pods carry no role node selector and schedule on that node

### Requirement: sysbench needs an app node for every command
Each `sysbench` script (`prepare`, `start`, `stop`) SHALL call `edl_require_app_node sysbench` before it creates its first pod.  When no node matches the app role, the script SHALL print `sysbench needs an app node (<selector>); this cluster has none`, create no pod and exit non-zero.  A failed `stop` leaves the kit marked as running.

#### Scenario: start with no app node
- **WHEN** `sysbench-<target> start` runs on a cluster where no node matches `APP_NODE_LABELS=type=app`
- **THEN** it fails with `sysbench needs an app node (type=app); this cluster has none`
- **AND** it runs no `kubectl run`

#### Scenario: prepare with no app node
- **WHEN** `sysbench-<target> prepare` runs on a cluster with no app node
- **THEN** it fails with the app-node message and runs no `kubectl run`

#### Scenario: sysbench stop fails with no app node
- **WHEN** `sysbench-<target> stop` runs on a cluster with no app node
- **THEN** it fails with the app-node message and exits non-zero
- **AND** the kit stays in `runningKits`
