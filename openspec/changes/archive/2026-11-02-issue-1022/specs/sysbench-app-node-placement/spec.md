## ADDED Requirements

### Requirement: sysbench pods run on app nodes
Every pod that the `sysbench` kit creates (the run pod, the prepare pod, the createdb pod and the cleanup pod) SHALL carry `nodeSelector: {type: app}`, set with `edl_pod_overrides` from the shared kit shell library.  The `sysbench` scripts SHALL source the library through `EDL_KIT_LIB`.

#### Scenario: Run pod on an app node
- **WHEN** `sysbench-<target> start` runs on a cluster with an app node
- **THEN** the sysbench run pod is created with `nodeSelector` `type=app`
- **AND** it is scheduled on a node labelled `type=app`

#### Scenario: Prepare pods on an app node
- **WHEN** `sysbench-<target> prepare` runs on a cluster with an app node
- **THEN** the createdb pod and the prepare pod are created with `nodeSelector` `type=app`

#### Scenario: Cleanup pod on an app node
- **WHEN** `sysbench-<target> stop` runs on a cluster with an app node
- **THEN** the cleanup pod is created with `nodeSelector` `type=app`

#### Scenario: Run pod on the app node of a real cluster
- **WHEN** `sysbench` runs on a cluster with 1 app node and 3 db nodes
- **THEN** its run pod is on the app node

### Requirement: sysbench needs an app node for every command
Each `sysbench` script (`prepare`, `start`, `stop`) SHALL call `edl_require_app_node sysbench` before it creates its first pod.  When the cluster has no app node, the script SHALL print `sysbench needs an app node (type=app); this cluster has none`, create no pod and exit non-zero.  A failed `stop` leaves the kit marked as running.

#### Scenario: start with no app node
- **WHEN** `sysbench-<target> start` runs on a cluster with no app node
- **THEN** it fails with `sysbench needs an app node (type=app); this cluster has none`
- **AND** it runs no `kubectl run`

#### Scenario: prepare with no app node
- **WHEN** `sysbench-<target> prepare` runs on a cluster with no app node
- **THEN** it fails with the app-node message and runs no `kubectl run`

#### Scenario: sysbench stop fails with no app node
- **WHEN** `sysbench-<target> stop` runs on a cluster with no app node
- **THEN** it fails with the app-node message and exits non-zero
- **AND** the kit stays in `runningKits`

### Requirement: sysbench waits for its pods to leave Pending
The `sysbench` scripts SHALL wait for each pod with `edl_wait_for_pod_running`, in place of `kubectl wait --for=condition=ready --timeout=60s`.  A pod that ends before it is seen Running SHALL not make the script wait for the limit.  The `prepare` script SHALL wait for each pod to exit with `edl_wait_for_pod_exit` and SHALL fail when a pod exits non-zero.

#### Scenario: Createdb pod finishes in one second
- **WHEN** the createdb pod is Succeeded on the first poll
- **THEN** `prepare` goes on at once to the next step

#### Scenario: Run pod fails before it is Ready
- **WHEN** the run pod reaches phase Failed before it was ever Ready
- **THEN** `start` reports the run failure with the exit code, not a timeout

#### Scenario: Bad image fails fast
- **WHEN** the run pod is Pending with waiting reason `ErrImagePull`
- **THEN** `start` fails at once with the reason and the describe events

### Requirement: sysbench start checks the run pod exit code
After the log stream ends, `sysbench-<target> start` SHALL wait for the run pod to exit with `edl_wait_for_pod_exit`.  When the exit code is not 0, it SHALL print that the run failed with the exit code, SHALL NOT print `Run summary written to ...`, and SHALL exit non-zero.  When the exit code is 0, it SHALL exit 0 as before.

#### Scenario: sysbench run exits non-zero
- **WHEN** the `sysbench` process in the run pod exits 1 after `FATAL: event queue is full`
- **THEN** `sysbench-<target> start` says that the run failed, gives exit code 1 and exits non-zero
- **AND** it does not print `Run summary written to ...`

#### Scenario: sysbench run exits 0
- **WHEN** the `sysbench` process in the run pod exits 0
- **THEN** `sysbench-<target> start` prints `Run summary written to ...` and exits 0

### Requirement: sysbench user guide states the app-node need
`docs/user-guide/sysbench.md` SHALL say that the sysbench pods run on an app node and that the cluster needs one for `prepare`, `start` and `stop`.

#### Scenario: Reader checks the sysbench guide
- **WHEN** a user reads `docs/user-guide/sysbench.md`
- **THEN** it says that sysbench pods run on an app node and that the cluster needs one
