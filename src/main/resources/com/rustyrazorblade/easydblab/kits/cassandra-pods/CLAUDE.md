# cassandra-pods kit

Cassandra as a StatefulSet on the db nodes. The built-in `cassandra` command group is the separate
SSH path for the AMI; this kit is the pods path, so its name must not match a built-in command
(`BuiltInCommands` refuses it).

Two paths, chosen by `--image`:

- **Tarball (default)**: `--version` (ASF) or `--build` (cassandra-builds), with `--java`. The
  `cassandra-artifact` contributor (`services/cassandraversions/CassandraArtifactContributor`)
  resolves the tarball, its sha256 and the `cassandra-runtime:<rev>-jre<N>` image before any step,
  and writes `resolved-artifact.env`, `cassandra.patch.yaml` (the workspace patch, or the common one),
  `placement.patch.yaml` and `cassandra-rackdc.properties` into the kit directory. `lib/apply-config.sh`
  puts them, with the `config/` scripts, in the `cassandra-pods-config` ConfigMap. Init containers:
  `otel-agent`, `cassandra-fetch` (`config/fetch-tarball.sh`: the sha256 cache on the claim,
  `.edl/artifacts/sha256-<hex>/.complete`) and `cassandra-render` (`config/render-config.sh`: the VM
  path's merge into the `conf` emptyDir). Cassandra runs `bin/cassandra -f` from the cache, with
  `CASSANDRA_INCLUDE` the rendered `cassandra.in.sh` (the tarball's plus the packer snippet). Template:
  `statefulset.yaml.template`.
- **`--image`**: today's image entrypoint path, `statefulset-image.yaml.template`. `lib/check-start.sh`
  looks the image up only on this path and writes `resolved-image.env` (`@RESOLVED_IMAGE@`,
  `@RESOLVED_BUILD@`). `--build` with `--image` is refused.

- `config/edl-cassandra-config.sh`, `config/edl-cassandra-agents.sh` and `config/cassandra.in.sh` are
  copied from `packer/cassandra/` by `processResources`; they are not checked in here, and
  `CassandraPodsKitTest` checks they match byte for byte. `config/` scripts run under the runtime image's
  `/bin/sh` and stay POSIX.
- `lib/apply-statefulset.sh` picks the template and fills its `@...@` placeholders literally (a presigned
  URL holds `&`). The resources are written `resources: {} # @EDL_CASSANDRA_RESOURCES@` (and
  `@EDL_INIT_RESOURCES@`), so the template stays valid YAML; the script replaces the `{}` and the comment
  with the contributor's JSON.
- Sizing (design D8): `--memory` defaults to 75% of the smallest db node where pods are Guaranteed
  (`GUARANTEED_PODS`), else `3Gi`; `--cpus` to its CPUs minus 1. On Guaranteed nodes every container,
  init containers included (`INIT_CONTAINER_CPU`/`INIT_CONTAINER_MEMORY`), has requests equal to limits.
- The pod template's `easydblab/config-hash` is the contributor's hash of the artifact (not its URLs),
  the config inputs and the `config/` scripts, so only a changed input rolls the pods.
- `upgrade --version|--build [--java]` is `bin/upgrade.sh`: it refuses a major-version downgrade (the
  running StatefulSet's `EDL_CASSANDRA_BUILD` against the new one) and an `--image` kit, applies the
  ConfigMap and the StatefulSet (`RollingUpdate`, `OrderedReady`, preStop drain), and waits for the
  rollout and UN. The runner records `CASSANDRA_VERSION`, `CASSANDRA_BUILD` and `CASSANDRA_JAVA`
  (`records:`) only on success.
- Clients dial the node: each pod sets `broadcast_rpc_address` to `status.hostIP` and its native
  port to the NodePort value 30942, and the NodePort Service uses `externalTrafficPolicy: Local`.
  Every address a driver reads from `system.peers_v2` therefore reaches the pod on that node.
- The OTel agent is copied by an init container from `__OTEL_AGENT_IMAGE__` (`JavaAgent.OTEL`, the
  image every kit and stress job uses) into the `java-agents` emptyDir, mounted at `/edl/agents`.
- `cassandra-jmx-rules.yaml` is copied into this kit's resources by `processResources` from
  `configuration/cassandra/`; it is not checked in here.
- `lib/check-start.sh` checks for the AMI Cassandra over ssh only when `EDL_NODE_SSH=true` (set by
  `KitProcessEnvironment` from the recorded `NODE_SSH` capability); on `false` it prints one skip line. It also validates `--nodes` (whole number >= 1), `--heap` (whole number of G or M), `--memory` (whole number of Gi or Mi, with `--heap` smaller than it) and `--cpus` (whole number >= 1); a private image (HTTP 401/403) only warns, a 404 fails. `lib/wait-un.sh` bounds the wait at 300s + 720s per node (`CASSANDRA_UN_WAIT_SECONDS` overrides it for tests).
- Tests: `CassandraPodsKitTest` (both templates through the real `lib/apply-statefulset.sh`),
  `./gradlew testCassandraPodsStart testCassandraPodsUpgrade testCassandraPodsFetch testCassandraPodsRender`.
- The pods run as uid 999 with `fsGroup: 999` and meet Pod Security "restricted" on the tarball path.
- `stop` never deletes claims; only `uninstall` does.
- `metrics-catalog.json` is empty until exported from a live cluster (`bin/export-workload-metrics`).
- The image's `cassandra-env.sh` sizes the heap from host memory, not the cgroup limit, so the pod always
  exports `MAX_HEAP_SIZE`: `--heap`, else half of `MEMORY_LIMIT_MIB` (the limit, from the downward API).
  The readiness probe matches `running` as a whole line (`grep -qx`); `not running` contains `running`.
- Local clusters: request equals limit, so N db pods reserve N x `--memory` (default 3Gi there). 2Gi was
  OOM-killed in testing; do not go below about 3Gi without testing it.
