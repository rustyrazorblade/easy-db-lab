## 1. Engine: kit args (E1, E2)

- [x] 1.1 Regression tests first (unit tier): `KitRunnerCommandFactory` records no value for an unset optional arg with no default (today it stores `"null"`, `KitRunnerCommandFactory.kt:192`); a boolean command arg is `false` when omitted and `true` when given; an arg with a default still injects the default; sysbench and kafka command args produce the same environment as before.
- [x] 1.2 Add `KitArgSpec.repeatable` (default `false`) in `services/KitConfig.kt`. `KitConfig` load rejects `repeatable: true` on a top-level `args:` entry and on a non-string arg, with an error that names the arg. Tests for both rejections.
- [x] 1.3 Add one shared arg-option builder (e.g. `commands/install/KitArgOptions.kt`, with class KDoc) that builds picocli `OptionSpec`s: no value recorded for an unmatched optional arg, booleans default `false`, repeatable string args as an accumulating `List<String>` joined with `\n`. Use it from `KitInstallCommandFactory` and `KitRunnerCommandFactory`; remove the duplicated option code.
- [x] 1.4 Factory tests: `--env A=1 --env B=2` yields `A=1\nB=2`; one value yields that value; an unset repeatable arg is not recorded; install and command args with the same spec behave the same.
- [x] 1.5 `./gradlew detekt` on JDK 21 shows no new findings in the touched files; fix any with extraction.

## 2. Engine: `kit info` command args (Q19)

- [x] 2.1 Test: `KitInfo` output for a kit with `commands: start: args:` lists each arg under `start` with flag, variable, description, default, and a repeatable marker.
- [x] 2.2 Implement in `commands/kit/KitInfo.kt` (extract a helper rather than grow the builder method).

## 3. Engine: `start` fails on metrics or dashboard failures (Q20)

- [x] 3.1 Add typed events in `events/Event.kt` (e.g. `Event.Kit.MetricsRegistrationFailed(kit, reason)` and `Event.Grafana.KitDashboardInstallFailed(kit, dashboard, reason)`) with console rendering and serialization registration per `events/CLAUDE.md`.
- [x] 3.2 Tests (fake `MetricsRegistryService` / Grafana client, not Mockito-stubbed `Result`): a failed registration emits the event and `start` exits non-zero; a rejected dashboard install emits the event and `start` exits non-zero; an unreadable dashboard tree (`KitDashboardsSkipped` path) and a missing declared dashboard file fail `start` non-zero.
- [x] 3.3 Implement in `commands/install/KitRunnerCommand.kt` (replace the `log.warn` at `:374-380` and `:473-476`).

## 4. Test helper

- [x] 4.1 Move `StubKubectl` out of `Neo4jKitTest.kt` into its own test file with class KDoc; add canned replies for `kubectl get` (pod status JSON, logs). `Neo4jKitTest` still passes.

## 5. Node: kubelet ECR credential provider (Q9, Q18)

- [x] 5.1 Add `packer/base/install/install_ecr_credential_provider.sh`: install the pinned `ecr-credential-provider` binary for the build arch and a `CredentialProviderConfig` matching `*.dkr.ecr.*.amazonaws.com`, at the path the K3s kubelet reads. Wire it into `packer/base/base.pkr.hcl`.
- [x] 5.2 Packer script test (`./gradlew testPackerScript -Pscript=base/install/install_ecr_credential_provider.sh`, plus a stubbed shell unit test if the script has branching logic) asserting the binary and config are in place and the config matches the ECR host pattern.
- [x] 5.3 If K3s does not honor `/var/lib/rancher/credentialprovider/` by default, add `--kubelet-arg=image-credential-provider-bin-dir=...` and `--kubelet-arg=image-credential-provider-config=...` to `start_k3s_server.sh` and `start_k3s_agent.sh`. Record which mechanism was used in a script comment.
- [x] 5.4 Delete `services/EcrPullSecretService.kt` and `EcrPullSecretServiceTest.kt`; remove it from `ServicesModule.kt` and `TestModules.kt`. `StressJobService` and `SidecarService` (and the sidecar manifest builder) stop creating `ecr-pull-secret` and stop setting `imagePullSecrets`.
- [x] 5.5 Update `DefaultStressJobServiceTest` and the sidecar tests: the built job / DaemonSet has no `imagePullSecrets` and no Secret is created for an ECR image.
- [x] 5.6 Grep source, docs and every `CLAUDE.md` for `EcrPullSecretService`, `ecr-pull-secret` and `imagePullSecret`; remove or update every mention.

## 5b. Node: data on a non-root volume, checked in advance (owner requirement)

- [x] 5b.1 Tests first: `init` fails, naming the node type and instance type, when the control or app instance type has no instance store (whatever `--ebs.type` is); the default db, control and app instance types pass; the existing db rules (instance store, or `--ebs.type` not `NONE`) are unchanged. Use the existing `DescribeInstanceTypes` path.
- [x] 5b.2 Implement the `init` validation for control and app instance types alongside the existing db check.
- [x] 5b.3 `setup_instance.sh`: find the data disk among every unused non-root block device (not only `nvme0n1`, `nvme1n1`, `xvdb`), format and mount it at `/mnt/db1`; exit non-zero with a message naming the reason when no data disk is found, when the mount fails, or when `/mnt/db1` is not a mount point of a non-root device afterwards. Never fall back to a plain `/mnt/db1` directory on root.
- [x] 5b.4 A stubbed shell unit test for `setup_instance.sh` (no Docker, same style as `testCassandraScripts`/`testBaseJdkInstall`, wired into Gradle): disk found and mounted; no disk → non-zero; mount fails → non-zero; data disk named `nvme2n1` → found.
- [x] 5b.5 `SetupInstance` (and `up`): a non-zero setup on any node fails `up` with an error naming the host and the reason, before K3s starts. Test it.

## 6. FerrosaDB kit files

- [x] 6.1 `kits/ferrosa/kit.yaml`: `type: db`, `collision-check: true`, runtime selector `easydblab/kit=ferrosa`, scrape metrics (job `ferrosa`, `pod-selector: app.kubernetes.io/name=ferrosa`, port 9090, `/metrics`), seven endpoints (`node-type: db`), install `platform-pvs` on db nodes, `commands: start: args:` for the seven options with the variables and defaults in design.md, `start` steps 1–6, `stop` and `uninstall` steps.
- [x] 6.2 `ferrosa-node.yaml.template` (top level): PVC `ferrosa-data-<i>`, Deployment `ferrosa-<i>`, ClusterIP Service `ferrosa-<i>`, per design.md "Per-pod spec".
- [x] 6.3 `nodeport-service.yaml.template`: seven NodePort Services selecting `easydblab/ferrosa-ordinal=0`, ports 30942, 30909, 30787, 30747, 30880, 30532, 30815.
- [x] 6.4 `start` shell steps: validation gate; `ferrosa-settings` / `ferrosa-env` ConfigMaps; per-pod loop; readiness wait with pull/crash/timeout errors; heap-profile build check.
- [x] 6.5 `README.md.template` with connection details for each endpoint.

## 7. FerrosaDB kit tests

- [x] 7.1 `FerrosaKitTest` (extends `BuiltinKitFixture`/`BaseKoinTest`, uses `StubKubectl`, real `TemplateService`): run `start` for N=1 and N=3, parse applied YAML with fabric8, and assert Deployments/PVCs/Services, affinity, labels, no `hostPort`/`hostNetwork`, `imagePullPolicy: Always`, no `imagePullSecrets`, `terminationGracePeriodSeconds: 90`, host ids, seeds (excluding self; unset for N=1), broadcast, `FERROSA_EXPECTED_CLUSTER_SIZE` (N=3 set, N=1 unset), `publishNotReadyAddresses`, `envFrom` order, image selection (default, `--version`, `--image`), `s3` vs `local` settings, `MALLOC_CONF` with and without `--heap-sample`.
- [x] 7.2 `FerrosaKitTest`: every validation gate (`--image`+`--version`, bad `--storage`, `--heap-sample` alone, malformed `--env`, each per-pod `--env` key) fails with the right message and no mutating `kubectl` call.
- [x] 7.3 `FerrosaKitTest`: readiness wait fails naming pod and image on `ImagePullBackOff`, prints the previous log on `CrashLoopBackOff`, names non-ready pods on timeout; the heap-profile check fails naming the image when a pod log has `Invalid conf pair: prof`.
- [x] 7.4 Confirm the kit-wide suites pass with the new kit: `BuiltinKitNodePortTest`, `NodePortKitScrapeTest`, `PlatformPvReservationTest`, `BuiltinKitCollisionCheckTest`, `DashboardDatasourceVariablesTest`, `ClusterFilterTest`, `SeriesClusterTest`.
- [x] 7.5 Integration tier: apply the generated FerrosaDB manifests to K3s TestContainers and assert they are accepted.
- [ ] 7.6 Run `./gradlew test` and `./gradlew integrationTest` in a subagent; then `./gradlew ktlintFormat ktlintCheck detekt` on JDK 21. All green.

## 8. Docs and repo guidance

- [x] 8.1 `docs/user-guide/ferrosa.md` and a `docs/SUMMARY.md` entry, covering every item in the ferrosa-kit docs requirement, including the TOML override, the `sh` init-container need, and db-node contention.
- [x] 8.2 `docs/reference/ports.md` (seven NodePorts), `docs/user-guide/kits.md` (ferrosa listed).
- [x] 8.3 `docs/development/kits.md`: repeatable args, unset optional args are not set, `kit info` lists command args, `start` fails on metrics registration or dashboard failure.
- [x] 8.4 The docs for stress and sidecar custom images: ECR images pull through the node credential provider with no pull secret.
- [x] 8.5 Root `CLAUDE.md`: the metrics ConfigMap is `easydblab-metrics-<kit>-<job>`; `docs/user-guide/platform-substrate.md:221` likewise. `commands/CLAUDE.md`: dashboard failures (including `KitDashboardsSkipped`) now fail `start`.

## 9. AMIs

- [ ] 9.1 `./gradlew installDist`, then `build-image` (base and Cassandra AMIs).

## 10. Real cluster validation

- [ ] 10.1 Write `test-plans/ferrosa-kit-3node.md` with `/easy-db-lab:plan` (`bin/easy-db-lab`), covering every acceptance criterion: default/`--version`/ECR `--image` pulls, missing tag error, `--image`+`--version` error, a stress or sidecar ECR pull with no pull secret, N Deployments on db hosts only, one ring, a pod restart with a new IP rejoining, readiness, both storage modes (S3 objects under `ferrosa/`, nothing in S3 for `local`), invalid `--storage`, S3 failure fail-fast, seven NodePorts on every db node, `kit info ferrosa` endpoints and options, CQL round trip with no credentials, runtime options and `--env` rules, heap profiling on a real profiling build (the upstream `ferrosa-profiling` OCI archive, loaded, tagged and pushed to the account ECR, run with `--image`) with `.heap` files on the PV, and failure on a non-profiling build, a KeyValue cassandra-easy-stress run, metrics/logs/CPU profiles, `stop` keeping PVs, `.heap` files and S3 objects, start again after stop, and on every node (db, control, app) `findmnt /mnt/db1` shows the data-disk device, plus on each db node `findmnt -T /mnt/db1/ferrosa` is that device and holds the FerrosaDB data files, and in each pod `/var/lib/ferrosa` is the PVC mount.
- [ ] 10.2 Run the plan with `/easy-db-lab:run test-plans/ferrosa-kit-3node.md`; fix every failure in this patch.
- [ ] 10.3 Export `metrics-catalog.json` from that cluster (`bin/export-workload-metrics ferrosa`) and commit it to the kit directory.
- [ ] 10.4 Write `METRICS.md` from the catalog; build `dashboards/ferrosa.json` with the `dashboard-editor` agent (`metrics_datasource` and `logs_datasource` pickers only; CQL throughput and latency panels).
- [ ] 10.5 `./gradlew installDist`, redeploy the dashboard, rerun the stress run, and verify every panel shows data in the `ferrosa` folder; a reviewer checks every metric name against the catalog.
- [ ] 10.6 Put the settled cassandra-easy-stress command (replication argument) into `docs/user-guide/ferrosa.md`.
