## Context

Issue 969 adds a built-in FerrosaDB kit for performance work on FerrosaDB. The design came from an architect proposal, an upstream research pass over FerrosaDB at commit `220aff3c`, a design-critic pass, and a one-question-at-a-time owner grilling session (2026-10-06/07). The owner's answers are recorded in `.spec-flow/design-decisions.md` and override the issue where they differ. The kit needs a few general engine and node changes: repeatable kit args, a fix for unset args, `kit info` listing command args, `start` failing on metrics/dashboard failures, and ECR pulls through the kubelet credential provider.

## Goals / Non-Goals

**Goals:** one-command FerrosaDB ring on the db nodes; custom ECR builds with no credential step that keep working on week-long clusters; runtime settings per run; CQL load with cassandra-easy-stress; metrics, logs, CPU profiles and a dashboard; the engine fixes the kit exposed, applied to every kit.

**Non-Goals:** traces, auth, pair mode, a count option or StatefulSet, mode switching, shipping heap profiles, image building, a load tool, automatic deletion of any data, a dashboard-catalog test.

## Decisions

### Engine: repeatable kit args (E1)
`KitArgSpec.repeatable: Boolean = false`. Only `string` args may set it, and only on `commands.<cmd>.args`; `KitConfig` rejects it on top-level install args at load time, because `resolved-args.env` stores one `KEY=VALUE` per line (`BaseInstallCommand.kt:70`) and a multi-line value would corrupt it. Picocli gets a `List<String>` option that accumulates; the values are joined with `\n` into the variable. Scripts read it with `while IFS= read -r line`.

### Engine: shared arg-option builder (E2)
One builder (e.g. `commands/install/KitArgOptions.kt`) builds picocli `OptionSpec`s for both `KitInstallCommandFactory` and `KitRunnerCommandFactory`. It never records a value for an optional arg the user did not give (today `KitRunnerCommandFactory.kt:192` stores `"$value"`, so an unset optional arg with no default becomes `"null"`), gives booleans a `false` default, and handles repeatable args. Existing sysbench and kafka command args all have defaults, so their behavior is unchanged; regression tests cover it.

### Engine: `kit info` lists command args (Q19)
`KitInfo` prints each `commands.<name>.args` list under its command name (flag, variable, description, default, repeatable marker), after the top-level args.

### Engine: `start` fails on metrics or dashboard failures (Q20)
`KitRunnerCommand` today logs a warning when `metricsRegistryService.register` fails (`KitRunnerCommand.kt:374-380`) and when a dashboard install fails (`:473-476`). Both become typed events (e.g. `Event.Kit.MetricsRegistrationFailed`, `Event.Grafana.KitDashboardInstallFailed`) and `start` exits non-zero, for every kit. The path that emits `Event.Grafana.KitDashboardsSkipped` (dashboards or tenant listing unreadable) and the missing-declared-file path also fail `start` (critic finding 9 says Q20 replaces the documented warning behavior). `commands/CLAUDE.md` is updated to match.

### Node: kubelet ECR credential provider (Q9 b, Q18 a)
The base AMI installs the `ecr-credential-provider` binary and a `CredentialProviderConfig` (`apiVersion: kubelet.config.k8s.io/v1`, `matchImages: ["*.dkr.ecr.*.amazonaws.com"]`, provider env/args for the instance role). K3s's kubelet is pointed at them through K3s's default credential-provider location (`/var/lib/rancher/credentialprovider/bin` and `/var/lib/rancher/credentialprovider/config.yaml`) or, if a real node shows K3s does not honor it, through explicit `--kubelet-arg=image-credential-provider-*` flags in `start_k3s_server.sh` / `start_k3s_agent.sh`. Which one is verified on a real node. The node role already has ECR read permissions (`providers/aws/AWSPolicy.kt:177-192`). `EcrPullSecretService` is deleted; `StressJobService` and `SidecarService` stop creating `ecr-pull-secret` and stop setting `imagePullSecrets`. The owner noted an AMI rebake is not a cost.

### Kit files
Under `src/main/resources/com/rustyrazorblade/easydblab/kits/ferrosa/`: `kit.yaml`, `ferrosa-node.yaml.template` (top level, so `PlatformPvReservationTest` and `BuiltinKitNodePortTest` scan it), `nodeport-service.yaml.template`, `README.md.template`, `METRICS.md`, `metrics-catalog.json`, `dashboards/ferrosa.json`. No `bin/` (every `bin/` file becomes a subcommand).

### kit.yaml
`type: db`, `collision-check: true`, runtime pods selector `easydblab/kit=ferrosa`, metrics `[{type: scrape, job: ferrosa, pod-selector: app.kubernetes.io/name=ferrosa, port: 9090, path: /metrics}]`. Install: `platform-pvs` on db nodes, creating PVs `data-ferrosa-<i>` pinned to db ordinal `i` (`DefaultK8sStorageOperations.kt:159-200`). All options are `commands: start: args:`:

| Flag | Variable | Default |
|---|---|---|
| `--version` | `FERROSA_TAG` | none (script applies `nightly`) |
| `--image` | `IMAGE` | none |
| `--storage` | `STORAGE_MODE` | `s3` |
| `--log-level` | `LOG_LEVEL` | `info` |
| `--heap-profile` | `HEAP_PROFILE` | `false` (boolean) |
| `--heap-sample` | `HEAP_SAMPLE` | none (script applies `19`) |
| `--env` | `EXTRA_ENV` | repeatable |

`STORAGE_SIZE` is reserved and cluster-state variables override arg variables, so no name may collide.

Endpoints (all `node-type: db`): CQL 30942 `cql`; console/metrics 30909 `http` path `/`; Bolt 30787 `native`; graph HTTP 30747 `http`; SPARQL 30880 `http`; Postgres stub 30532 `postgresql`; Flight 30815 `native`. `kit info` lists each on every db host, like other kits (Q7).

### start steps
1. **Validation gate** (shell, at the top of `start`, neo4j/memcached pattern; Q13): `--image` with `--version`; `--storage` not `local`/`s3` (error lists both); `--heap-sample` without `--heap-profile`; each `EXTRA_ENV` line must be `KEY=VALUE` (error names the line) and its key must not be a per-pod key (error names the key). Nothing is applied on failure.
2. **ConfigMaps** (shell): `ferrosa-settings` and `ferrosa-env`, via `kubectl create configmap --from-env-file ... --dry-run=client -o yaml | kubectl apply -f -`, both labelled `easydblab/kit=ferrosa`.
3. **Per-pod objects** (shell loop over `i` in `0..DB_NODE_COUNT-1`, substituting placeholders in the packaged `ferrosa-node.yaml.template`, piped to `kubectl apply -f -`; Q12): PVC `ferrosa-data-<i>` (`volumeName: data-ferrosa-<i>`), Deployment `ferrosa-<i>` (replicas 1, strategy `Recreate`), ClusterIP Service `ferrosa-<i>` (ports 17000 and 9042, `publishNotReadyAddresses: true`).
4. **NodePort Services** (manifest step, `nodeport-service.yaml`), each selecting `easydblab/ferrosa-ordinal=0` (Q4).
5. **Readiness wait** (shell): poll every 5 s, default timeout 600 s. Reads `initContainerStatuses` and `containerStatuses`. Fails at once on `ErrImagePull` / `ImagePullBackOff` / `InvalidImageName`, naming the pod and the full image. Fails on `CrashLoopBackOff` or a terminated init container, printing the tail of `kubectl logs --previous`. On timeout names every non-ready pod.
6. **Heap-profile build check** (shell, only when `HEAP_PROFILE=true`; Q16): search each pod's log for `Invalid conf pair: prof`; if found, fail with "`--heap-profile` needs a FerrosaDB profiling build" naming the image.

### Per-pod spec
Labels `easydblab/kit=ferrosa`, `app.kubernetes.io/name=ferrosa`, `app.kubernetes.io/instance=ferrosa`, `easydblab/ferrosa-ordinal=<i>`. Node affinity `type In [db]`. No `hostPort`/`hostNetwork`. `securityContext` uid 10001 gid 101. Init container (same image, `runAsUser: 0`) runs `mkdir -p /var/lib/ferrosa /var/lib/ferrosa/heap-profiles` and a non-recursive `chown 10001:101`. `terminationGracePeriodSeconds: 90`. `imagePullPolicy: Always`. `envFrom: [ferrosa-settings, ferrosa-env]` (later wins, so `--env` beats a named option; Q11). `env:` `POD_IP` (`status.podIP`), `FERROSA_HOST_ID` = `00000000-0000-0000-0000-%012x` of `i+1`, `FERROSA_INTERNODE_BROADCAST=ferrosa-<i>.default.svc.cluster.local:17000`, `FERROSA_SEED` = the other pods' Service names with `:17000`, comma-joined (unset for N=1), `FERROSA_CQL_BROADCAST=$(POD_IP):9042`, `FERROSA_FLIGHT_BROADCAST=$(POD_IP):8815`, `FERROSA_CLUSTER_NAME` from `cluster-config` key `cluster_name` (Q15), `FERROSA_EXPECTED_CLUSTER_SIZE=N` when N ≥ 3 (Q3/Q6). Readiness probe `httpGet /readyz :9090`.

Kubernetes `env:` beats every `envFrom` source, so `--env` cannot override a per-pod key; the gate rejects those keys instead (Q11).

### ferrosa-settings
`FERROSA_CQL_BIND=0.0.0.0:9042`, `FERROSA_WEB_BIND=0.0.0.0:9090`, `FERROSA_POSTGRES_BIND=0.0.0.0:5432`, `FERROSA_GRAPH_BIND=0.0.0.0:7474`, `FERROSA_BOLT_PORT=7687`, `FERROSA_SPARQL_BIND=0.0.0.0:8080`, `FERROSA_FLIGHT_BIND=0.0.0.0:8815`, `FERROSA_INTERNODE_BIND=0.0.0.0:17000`, `FERROSA_DATA_DIR=/var/lib/ferrosa` (one directory for both modes; Q8 b), `RUST_LOG`, `FERROSA_DATA_CENTER=${REGION}` (matches the cassandra-easy-stress default DC, `StressJobService.kt:474`), `FERROSA_RACK`.
- `s3` mode: `FERROSA_S3_ENDPOINT=https://s3.${REGION}.amazonaws.com`, `FERROSA_S3_REGION=${REGION}`, `FERROSA_S3_BUCKET=${BUCKET_NAME}`, `FERROSA_S3_PREFIX=ferrosa/`, `FERROSA_S3_REQUIRED=true`. Credentials come from the instance profile through IMDSv2 (hop limit already 2, `EC2InstanceService.kt:153`).
- heap: `MALLOC_CONF=prof:true,prof_active:true,prof_final:true,prof_prefix:/var/lib/ferrosa/heap-profiles/ferrosa,lg_prof_sample:<n>` (default 19). One shared prefix, in the shared ConfigMap, so `--env MALLOC_CONF=...` can override it.

### stop and uninstall
`stop` deletes by selector `easydblab/kit=ferrosa` the kinds deployment, replicaset, pod, service, configmap. PVCs are kept: a deleted PVC leaves its `Retain` PV `Released`, and the next `start` could not bind it. `uninstall` does the same plus `pvc`, then `platform-pvs-delete` (as memcached). Nothing deletes S3 data or `.heap` files.

### Observability
Metrics: the pod-selector scrape registers `easydblab-metrics-ferrosa-ferrosa` (`MetricsRegistryService.kt:61-62` builds `easydblab-metrics-<kit>-<job>`; Q23 corrects the root `CLAUDE.md` wording and the `kit-metrics-declaration` spec). The collector upserts `cluster` from `cluster-config` on every pipeline (`otel-collector-config.yaml:220-224`); the kit sets no cluster label and never uses the bare `CLUSTER_NAME` (`TemplateVariables.kt:38`). Logs: existing `/var/log/pods/**` collection. CPU profiles: existing Alloy `pyroscope.ebpf` with pod labels (`config.alloy:57-83`); frames resolve fully only on a profiling build. Heap profiles stay on the PV (Q17). Dashboard: built by the `dashboard-editor` agent from a `metrics-catalog.json` exported on a real cluster, naming datasources only through `metrics_datasource` and `logs_datasource`. No catalog test (Q22).

### Load
No code change: `cassandra stress start KeyValue -d 10m --host ferrosa-0.default.svc.cluster.local`. The stress pod is `hostNetwork` with `ClusterFirstWithHostNet` (`StressJobService.kt:581-582`), so it resolves the Service name. The exact replication argument is settled in the real run and written into the docs.

### Testing
Unit: `FerrosaKitTest` (`BuiltinKitFixture` + `StubKubectl` running the `start` shell for N=1 and N=3; applied YAML parsed with fabric8; every gate fails with no mutating `kubectl` call); factory tests (repeatable accumulates, unset optional arg not recorded, boolean flag true/false); `KitConfig` rejects repeatable on top-level and non-string args; `KitInfo` lists command args; `KitRunnerCommand` fails `start` on registration and dashboard failures; stress and sidecar tests assert no pull secret. Existing suites pick the kit up (`BuiltinKitNodePortTest`, `NodePortKitScrapeTest`, `PlatformPvReservationTest`, `BuiltinKitCollisionCheckTest`, `DashboardDatasourceVariablesTest`, `ClusterFilterTest`, `SeriesClusterTest`). Integration: apply the generated manifests to K3s. Packer: a credential-provider install script test. Real cluster: a 3-node test plan under `test-plans/` run via `/easy-db-lab:run`.

## Alternatives Considered

"Rec" is the advisor recommendation at the time of the question.

- **Q1 Traces.** (a, rec, chosen) remove the traces AC, set no OTel variables, file a follow-up (#995). (b) a placeholder `FERROSA_OTEL_ENDPOINT` with the AC open as blocked — rejected: untestable configuration. (c) keep traces and wait for upstream — rejected: blocks the kit indefinitely.
- **Q2 Scope.** (a, rec, chosen) allow small general engine changes. (b) YAML only, dropping repeatable `--env` and ECR pulls — rejected: removes features. (c) split engine changes into a dependency issue — rejected.
- **Q3 Readiness / `FERROSA_EXPECTED_CLUSTER_SIZE`.** Chosen originally: N ≥ 3 sets N on every pod, N = 2 primary only, N = 1 unset; Q5/Q6 removed pair mode, so the final rule is N ≥ 3 sets N, N < 3 unset. Rejected for N = 2: (b) set 2 on both — the secondary stays 503 forever, breaking "all pods ready"; (c) never set — no membership proof.
- **Q4 NodePort targeting.** (a, rec, chosen) all seven NodePorts select ordinal 0; drivers find peers via `system.peers`. (b) CQL and Postgres to ordinal 0, the rest to all pods — rejected. (c) ordinal 0 on N = 2, all pods on N ≥ 3 — rejected: the selector would depend on N.
- **Q5 Pair-mode driver problem.** No alternatives taken: the owner said pair mode is not used; it is out of scope.
- **Q6 Fewer than 3 db hosts.** (a, rec) fail `start` below 3 — not chosen. (b) fail on exactly 2, allow 1 — not chosen. **(c, chosen by the owner over the recommendation)** allow any count; the docs say only 3 or more is tested.
- **Q7 Host index on endpoints (E4).** (a, rec, chosen) drop E4; `kit info` lists the CQL endpoint on every db host. (b) keep E4 — rejected: no AC needs it once pair mode is out.
- **Q8 Data directory layout.** (a, original rec) per-mode subdirectories `data-s3` / `data-local` — rejected by the owner: switching modes is not a supported operation. **(b, chosen; the recommendation changed to b after the owner's input)** one directory `/var/lib/ferrosa`, docs say switching is unsupported, no check. (c) a marker file that fails `start` on a mode mismatch — rejected: extra code for an unsupported operation.
- **Q9 ECR pulls and pull policy.** (a, initial rec) an E3 `ecr-pull-secret` kit step calling `EcrPullSecretService`, `Always`, 12 h token expiry documented — rejected: on a week-long cluster a pod restart after 12 h cannot pull. **(b, chosen; the recommendation changed to b after the owner raised week-long clusters)** the kubelet ECR credential provider on every node via the base AMI; E3 dropped; `imagePullPolicy: Always`. (c) E3 with `IfNotPresent` — rejected: a new build would need a new tag.
- **Q10 `--image` with `--version`.** (a, rec, chosen) `start` fails before any pod; `--version` has no `kit.yaml` default and the script applies `nightly`. (b) `--image` wins silently — rejected.
- **Q11 `--env` precedence.** (a, rec, chosen) fail before any pod when an `--env` key is a per-pod key, naming the key; heap config lives in the shared ConfigMap so `--env MALLOC_CONF` can override it; `envFrom` order `[ferrosa-settings, ferrosa-env]`. (b) allow overriding per-pod keys through a per-pod ConfigMap — rejected: cannot express `$(POD_IP)`.
- **Q12 Per-pod rendering.** (a, rec, chosen) a shell loop over the packaged template piped to `kubectl apply -f -`. (b) a new general `for-each: db` manifest step — rejected: it still cannot express "seeds = everyone but me"; build it when a second kit needs it.
- **Q13 `--storage` validation.** (a, rec, chosen) a shell check at the top of `start`. (b) a general `choices:` field on kit args — rejected: extra engine surface.
- **Q14 `--auth`.** Options were ways to supply credentials for an `--auth` mode. The owner asked whether FerrosaDB runs without auth; it does (auth off by default). Chosen: run without auth; `--auth` and its ACs removed. **Q25:** no follow-up issue for auth.
- **Q15 `FERROSA_CLUSTER_NAME`.** (a, rec, chosen) from `cluster-config` `cluster_name` (`<name>-<id>`). (b) upstream default `ferrosa` — rejected.
- **Q16 `--heap-profile` on a non-profiling image.** (a, rec, chosen) after readiness, search each pod's log for `Invalid conf pair: prof` and fail naming the image. (b) docs only — rejected. (c) `abort_conf:true` so jemalloc aborts — rejected: a crash loop is a less clear error.
- **Q17 `.heap` files lost on `down`.** (a, rec, chosen) accept until the heap-to-Pyroscope issue (#981) lands; docs show how to copy them off before `down`. (b) `stop` uploads `.heap` files to the account bucket — rejected: #981 covers shipping. (c) fold #981 in — rejected.
- **Q18 `EcrPullSecretService`.** (a, chosen) remove it in this patch; stress and sidecar stop creating `ecr-pull-secret` and stop setting `imagePullSecrets`. (b) remove it in a separate issue — rejected.
- **Q19 `kit info` lists only top-level args.** (a, rec, chosen) fix here. (b) separate issue — rejected.
- **Q20 Silent metrics/dashboard failures.** (a, rec, chosen) typed events and `start` exits non-zero, for every kit. (b) event only, `start` still succeeds — rejected. (c) separate issue — rejected.
- **Q21 `StubKubectl`.** (a, rec, chosen) move it out of `Neo4jKitTest.kt` into its own test file with canned `kubectl get` replies. No other option was weighed.
- **Q22 Catalog test.** An automated dashboard-metric-in-catalog test was proposed. **The owner removed it:** no test is necessary for any kit; the AC stays a reviewer check.
- **Q23 Metrics ConfigMap name.** (a, chosen) the AC names `easydblab-metrics-ferrosa-ferrosa`; root `CLAUDE.md` wording corrected in this patch.
- **Q24 Upstream FerrosaDB issue for OTLP.** (a, rec) draft one for the owner — not chosen. **(b, chosen by the owner over the recommendation)** no upstream issue is filed.
- **Q26 `down` reporting bug.** Out of this patch; filed as #996.

Seam 1 review (2026-10-07/08):

- **`--trace-sample-rate`.** (a, rec, chosen) drop it and move it to the traces follow-up (#995): it sets `FERROSA_TELEMETRY_SAMPLE_RATE`, which upstream reads only when `FERROSA_TELEMETRY_ENABLED=true` (`ferrosa/src/main.rs:1568`), and then only for an in-process count of sampled spans; no span is exported. (b) keep it and also set `FERROSA_TELEMETRY_ENABLED=true` — rejected. (c) keep it as written — rejected: the option would set a value FerrosaDB ignores.
- **Dashboard read failures.** (a, rec, chosen) any failure to put a kit's dashboards into Grafana fails `start`, including unreadable dashboards or tenant listing and a missing declared file. (b) fail only when Grafana rejects a dashboard — rejected.
- **The 12-hour ECR re-pull AC.** (a) a one-time forced re-pull in the real-cluster plan — not chosen. **(b, rec, chosen)** remove the AC and its scenarios: the credential provider stores no token, so "an ECR image pulls with no pull secret" covers a long-running cluster.

## Domain Facts

Upstream FerrosaDB facts come from the upstream research consult over `ferrosadb/ferrosa` at commit `220aff3c` (2026-10-06); facts marked (verified) were re-read by the issue manager.

- No OTLP export: no `.rs` file uses the opentelemetry/otlp crates; `FERROSA_OTEL_ENDPOINT` appears only in design docs (verified).
- SIGTERM: a tokio select on ctrl_c/terminate (`ferrosa/src/main.rs:3339-3346`), a drain under a 30 s timeout (flush memtables, drain internode, sync SSTables to S3), then `main` returns `Ok` (`:3348-3396`), so jemalloc's `prof_final` runs. Hence `terminationGracePeriodSeconds: 90`.
- Pair mode: the lower host UUID is primary unless promoted (`ferrosa-cluster/src/controller/pair.rs:79-85`, verified); the secondary rejects CQL with `Overloaded` (`ferrosa-cql/src/server.rs:309-325`, verified) and is listed in peers (`ferrosa-cluster/src/state.rs:60-111`, verified). Readiness gate `readiness.rs:248-261` and `declared_topology_is_ready` `mod.rs:324-344` (verified): size ≥ 3 needs Cluster mode, a leader and N voters.
- `FERROSA_HOST_ID` is a UUID (`main.rs:1073-1079`). All client binds default to `127.0.0.1` and need IP literals (`main.rs:354-366`). Internode bind defaults to `0.0.0.0:17000`.
- `FERROSA_SEED` is a comma-separated `host:17000` list resolved by a background loop with 0.5 s–10 s backoff; `FERROSA_INTERNODE_BROADCAST` keeps the raw name and peers re-resolve it on each reconnect (`:212-227`, `:50-55`).
- S3: `FERROSA_S3_ENDPOINT` and `FERROSA_S3_BUCKET` required, region default `us-east-1`, prefix default empty; with `FERROSA_S3_REQUIRED=true` an unreachable or denied bucket exits the process with status 1 at startup with "S3 access failed and FERROSA_S3_REQUIRED is set: ..." (`ferrosa-storage/src/upload/config.rs:333-356`, `:397-420`); an unreachable endpoint can take about 3 minutes because of `object_store` retries. Startup lists/puts/deletes only its own probe object `.ferrosa/connectivity-check`. Credentials through the `object_store` default chain, including IMDSv2 (`config.rs:33`).
- `/metrics`: Prometheus text, unauthenticated, names prefixed `ferrosa_`, e.g. `ferrosa_cql_requests_total{kind,outcome}`, `ferrosa_cql_request_duration_seconds` (histogram, `kind`), `ferrosa_cql_requests_in_flight{kind}`, emitted from startup.
- Image: alpine, `USER ferrosa` UID 10001 GID 101, `ENTRYPOINT ferrosa`, `VOLUME /var/lib/ferrosa`; `:nightly` public, multi-arch amd64+arm64; tags `nightly`, `vYYYY.MM.DD.HHMM`, `latest`. Env-only configuration works; TOML wins over env when both are set; `/etc/ferrosa/ferrosa.toml` is absent from the image. Published images build with `--features ferrosa/full` (`full = ["otel", "flight", "ferrosa-cql/asc-udf"]`).
- jemalloc: the profiling feature (`tikv-jemallocator/profiling_libunwind`) is not in `full`; the profiling build is distributed only as a per-arch OCI tarball; the Linux build is unprefixed (`ferrosa/Cargo.toml:61`), so `MALLOC_CONF` applies (verified via `.github/workflows/release.yml:507-510`); without profiling, `prof:true` prints `<jemalloc>: Invalid conf pair: prof:true` and continues.
- Auth defaults off (`ferrosa-storage/src/engine.rs:1000`, verified); precedence TOML > `FERROSA_AUTH_ENABLED` > default (`ferrosa/src/main.rs:939-953`).
- cassandra-easy-stress (`apache/cassandra-easy-stress` `Run.kt:348-390`): one contact point via `--host`, optional `--dc`, no host allowlist.

From the live ECR pull test (2026-10-06, 1 control + 1 db node, K3s v1.35.1, flannel): a pod with a private ECR image, `imagePullPolicy: Always` and no `imagePullSecrets` went to `ImagePullBackOff` with "authorization failed: no basic auth credentials". No node had `/var/lib/rancher/credentialprovider/`, the kubelet had no credential flags, and `registries.yaml` held only the internal-registry TLS settings; `aws ecr get-login-password` worked on the node. Commit `60e58575` (#904) records that stress ECR images failed the same way until `EcrPullSecretService` was added.

## Risks / Trade-offs

- E2 changes arg handling for every kit. Sysbench and kafka command args all have defaults; regression tests cover them.
- detekt `CyclomaticComplexMethod` on touched methods (`KitInfo`, `KitRunnerCommand`, the factories): fix with extraction, never `@Suppress`, a threshold change or a baseline entry.
- The credential-provider config must match the account's ECR registry hosts (`*.dkr.ecr.*.amazonaws.com`), and K3s's credential-provider wiring must be verified on a real node.
- A custom image that bakes `/etc/ferrosa/ferrosa.toml` overrides the kit's env settings (TOML wins); a non-alpine custom image without `sh` breaks the init container. Both are documented.
- Resource contention if Cassandra (or another database) also runs on the db nodes. Documented.
- Dashboard install failures were documented as warnings (`commands/CLAUDE.md`, `KitDashboardsSkipped`); Q20 changes that, so `commands/CLAUDE.md` is updated.
- The default tag is `nightly`; metric names can change between nightly builds (owner accepts).
- `.heap` files are lost with the node disk on `down` until #981; docs show how to copy them off.
- An unreachable S3 endpoint can take about 3 minutes to fail upstream; the 600 s readiness timeout covers it.

## Migration Plan

None. Clusters are ephemeral; new AMIs are baked and new clusters pick them up.
