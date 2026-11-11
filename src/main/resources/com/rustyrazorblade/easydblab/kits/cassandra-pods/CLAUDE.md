# cassandra-pods kit

Cassandra as a StatefulSet on the db nodes. The built-in `cassandra` command group is the separate
SSH path for the AMI; this kit is the pods path, so its name must not match a built-in command
(`BuiltInCommands` refuses it).

- Clients dial the node: each pod sets `broadcast_rpc_address` to `status.hostIP` and its native
  port to the NodePort value 30942, and the NodePort Service uses `externalTrafficPolicy: Local`.
  Every address a driver reads from `system.peers_v2` therefore reaches the pod on that node.
- The OTel agent is copied by an init container from `autoinstrumentation-java:<agent version>`.
  `@OTEL_AGENT_VERSION@` in `statefulset.yaml.template` is stamped from the version catalog by
  `processResources`; do not edit the tag by hand.
- `cassandra-jmx-rules.yaml` is copied into this kit's resources by `processResources` from
  `configuration/cassandra/`; it is not checked in here.
- `lib/check-start.sh` also validates `--nodes` (whole number >= 1), `--heap` (whole number of G or M), and `--memory` (whole number of Gi or Mi, with `--heap` smaller than it); a private image (HTTP 401/403) only warns, a 404 fails. `lib/wait-un.sh` bounds the wait at 300s + 720s per node (`CASSANDRA_UN_WAIT_SECONDS` overrides it for tests).
- `lib/check-start.sh` resolves the image and writes `resolved-image.env`;
  `lib/apply-statefulset.sh` fills `@RESOLVED_IMAGE@` and `@RESOLVED_BUILD@`. Tests:
  `./gradlew testCassandraPodsStart` and `CassandraPodsKitTest`.
- `stop` never deletes claims; only `uninstall` does.
- `metrics-catalog.json` is empty until exported from a live cluster (`bin/export-workload-metrics`).
- The cassandra container requests and limits `--memory` (default `4Gi`). The image's `cassandra-env.sh`
  sizes the heap from host memory, not the cgroup limit, so the pod always exports `MAX_HEAP_SIZE`:
  `--heap`, else half of `MEMORY_LIMIT_MIB` (the limit, from the downward API). The readiness probe
  matches `running` as a whole line (`grep -qx`); `not running` contains `running`.
- Local clusters: request equals limit, so N db pods reserve N x `--memory`. On a 3-node K3s-in-docker
  cluster pass `--memory 3Gi` or less (the heap is half the limit, or set it with `--heap`), or run
  fewer nodes. 2Gi was OOM-killed in testing; do not go below about 3Gi without testing it.
