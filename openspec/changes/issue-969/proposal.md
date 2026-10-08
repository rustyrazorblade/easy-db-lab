## Why

easy-db-lab cannot run FerrosaDB, a Rust reimplementation of Cassandra that serves CQL v3/v4, Bolt/Cypher, a graph HTTP API, SPARQL, Arrow Flight and a Postgres wire stub. The owner will tune FerrosaDB's performance and needs to start a ring on the db nodes with one command, run custom builds from ECR, change runtime settings between runs, drive a CQL load, and see metrics, logs and profiles in the observability stack. A live test also showed that a pod with no pull secret cannot pull from the account's ECR (`no basic auth credentials`), and the pull secret the stress and sidecar paths create expires after 12 hours, so a pod that restarts on a long-lived cluster cannot pull again.

## What Changes

- **New built-in kit `ferrosa`** (`src/main/resources/com/rustyrazorblade/easydblab/kits/ferrosa/`: `kit.yaml`, `ferrosa-node.yaml.template`, `nodeport-service.yaml.template`, `README.md.template`, `METRICS.md`, `metrics-catalog.json`, `dashboards/ferrosa.json`). It installs one platform PV for each db host and starts one Deployment (1 replica), one PVC and one ClusterIP Service for each db host, so the pods form one FerrosaDB ring with stable Service-name seeds and host ids pinned in host order.
- **Kit start options:** `--version` (tag of `ghcr.io/ferrosadb/ferrosa`, default `nightly`), `--image` (full reference, for ECR custom builds; error together with `--version`), `--storage=local|s3` (default `local`, data on the node's PV; `s3` keeps data in the cluster data bucket under `ferrosa/`, fail-fast with `FERROSA_S3_REQUIRED=true`), `--log-level`, `--heap-profile`, `--heap-sample`, and a repeatable `--env KEY=VALUE`. Every validation runs before anything is applied. `start` waits for `/readyz` on every pod and names the pod and image on a pull error, prints the previous log on a crash, and fails `--heap-profile` on a non-profiling build.
- **Client access:** seven fixed NodePorts (30942 CQL, 30909 console/metrics, 30787 Bolt, 30747 graph HTTP, 30880 SPARQL, 30532 Postgres stub, 30815 Flight), all selecting the first pod, each declared as an endpoint so `kit info` shows it. Auth is off.
- **Observability:** a pod-discovery `scrape` metrics entry (ConfigMap `easydblab-metrics-ferrosa-ferrosa`), logs through the existing container log collection, CPU profiles through the existing Alloy eBPF profiler, a dashboard in the `ferrosa` folder built from an exported `metrics-catalog.json`, and `METRICS.md`.
- **Repeatable kit args (engine):** `repeatable: true` on a string command arg; values are joined with a newline. Rejected on top-level install args and on non-string args.
- **Unset-arg fix (engine):** one shared arg-option builder for `KitInstallCommandFactory` and `KitRunnerCommandFactory`. An unset optional arg with no default is no longer recorded as the string `"null"` (`KitRunnerCommandFactory.kt:192`); booleans default to `false`.
- **`kit info` lists command args:** each `commands.<phase>.args` list under its command name, not only the top-level install args.
- **`start` fails on metrics or dashboard failures, for every kit:** a failed metrics registration or a failed dashboard install emits a typed event and `start` exits non-zero; today both only log a warning.
- **ECR pulls through the kubelet credential provider (node):** the base AMI installs `ecr-credential-provider` and its config, and every K3s kubelet uses it. **`EcrPullSecretService` is removed**; the stress and sidecar paths stop creating `ecr-pull-secret` and stop setting `imagePullSecrets`. Requires a base and Cassandra AMI rebake.
- **Data on a non-root volume, checked in advance (node):** `init` requires instance store on the control and app instance types too (db keeps instance store or `--ebs.type`). `setup_instance.sh` finds the data disk on any non-root device name, mounts it at `/mnt/db1`, and fails `up`, naming the host, if there is no disk, the mount fails, or `/mnt/db1` is not a non-root mount. Today it silently puts `/mnt/db1` on the 20 GB root volume.
- **Test helper:** `StubKubectl` moves out of `Neo4jKitTest.kt` into its own test file and gains canned `kubectl get` replies.
- **Docs and repo guidance:** `docs/user-guide/ferrosa.md` + `docs/SUMMARY.md`, `docs/reference/ports.md`, `docs/user-guide/kits.md`, `docs/development/kits.md` (repeatable args, `kit info` command args, start failure on metrics/dashboard), the stress and sidecar custom-image docs (ECR through the node provider), root `CLAUDE.md` (metrics ConfigMap name is `easydblab-metrics-<kit>-<job>`), `commands/CLAUDE.md` (dashboard failures now fail `start`).

## Capabilities

### New Capabilities
- `ferrosa-kit`: the FerrosaDB kit — image selection, topology and ring formation, storage modes, NodePorts and endpoints, runtime settings and validation, heap profiling, readiness, lifecycle, observability, load, docs.

### Modified Capabilities
- `kit-command-args`: shared arg builder with no `"null"` for unset args; repeatable command args; `kit info` lists command args.
- `kit-metrics-declaration`: ConfigMap name corrected to `easydblab-metrics-<kit>-<job>`; a failed registration fails `start` with a typed event.
- `typed-install-steps`: a failed kit dashboard install fails `start` with a typed event.
- `ami-building`: kubelet ECR credential provider on every node; no pull secrets.
- `instance-storage-validation`: every node type needs a data disk at `init`; `up` fails if `/mnt/db1` is not mounted on a non-root device.
- `stress-testing`: custom ECR stress images pull through the node provider, with no pull secret.
- `containerized-sidecar`: custom ECR sidecar images pull through the node provider, with no pull secret.

## Impact

- Code: `commands/install/KitRunnerCommandFactory.kt`, `KitInstallCommandFactory.kt`, a new shared arg-option builder (e.g. `commands/install/KitArgOptions.kt`), `services/KitConfig.kt` (`KitArgSpec.repeatable` and its load-time checks), `commands/kit/KitInfo.kt`, `commands/install/KitRunnerCommand.kt`, `events/Event.kt` (new typed failure events), `services/StressJobService.kt`, `services/SidecarService.kt` and its manifest builder, `services/ServicesModule.kt`; delete `services/EcrPullSecretService.kt` and its test.
- Packer: a new install script under `packer/base/install/` for the credential provider, its wiring into `base.pkr.hcl`, and a packer script test; the K3s server and agent start scripts if K3s's default credential-provider paths are not honored.
- Tests: `FerrosaKitTest`, a `StubKubectl` test file, factory/`KitConfig`/`KitInfo`/`KitRunnerCommand` tests, updated `DefaultStressJobServiceTest` and sidecar tests, `TestModules.kt`; existing kit-wide suites pick up the new kit.
- AMIs: base and Cassandra images rebaked (`./gradlew installDist`, then `build-image`).

## Out of scope

- A load tool in the kit (ferrosa-loadgen, NB5).
- Building FerrosaDB images, or loading the upstream `index.json` / `oci.tar` archives.
- The control-node image registry.
- Shipping `.heap` files to Pyroscope (separate issue).
- The OpenTelemetry Java agent (FerrosaDB is not a JVM).
- A count or replicas option, and a StatefulSet topology.
- S3 lifecycle or expiry rules, or any automatic deletion of FerrosaDB data or heap-profile files.
- Backup and restore phases.
- Bench kit targeting (`kit-ref` args) beyond the endpoint declarations.
- Traces (upstream has no OTLP exporter; follow-up issue #995), including a `--trace-sample-rate` option.
- Authentication (`--auth`).
- Two-node pair-mode support and tests.
- Switching `--storage` modes on an existing PV, and any check for it.
- An automated test that checks dashboard metric names against `metrics-catalog.json`.
- An `ecr-pull-secret` kit step and a host-index endpoint field.
- Filing an upstream FerrosaDB issue; the `down` reporting bug (#996).
