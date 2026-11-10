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
- `lib/check-start.sh` resolves the image and writes `resolved-image.env`;
  `lib/apply-statefulset.sh` fills `@RESOLVED_IMAGE@` and `@RESOLVED_BUILD@`. Tests:
  `./gradlew testCassandraPodsStart` and `CassandraPodsKitTest`.
- `stop` never deletes claims; only `uninstall` does.
- `metrics-catalog.json` is empty until exported from a live cluster (`bin/export-workload-metrics`).
