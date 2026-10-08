| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC Image | `start` with no `--version`/`--image` runs `ghcr.io/ferrosadb/ferrosa:nightly` | `ferrosa-kit: Default image` | ✅ Covered |
| AC Image | `--version=<tag>` runs `ghcr.io/ferrosadb/ferrosa:<tag>` | `ferrosa-kit: Version selects the tag` | ✅ Covered |
| AC Image | `--image=<ECR ref>` runs that image, pull succeeds with no manual credential step | `ferrosa-kit: Custom ECR image pulls with no credential step`, `ami-building: A pod pulls from ECR with no pull secret` | ✅ Covered |
| AC Image | Missing image or tag fails `start` naming image and tag | `ferrosa-kit: Missing image or tag fails start` | ✅ Covered |
| AC Image | `--image` with `--version` fails before any pod, says to use one | `ferrosa-kit: Image and version together are refused` | ✅ Covered |
| AC Image | `cassandra stress` or the sidecar pulls a custom ECR image with no pull secret | `stress-testing: Custom ECR stress image pulls with no pull secret`, `containerized-sidecar: Custom ECR sidecar image pulls with no pull secret` | ✅ Covered |
| AC Topology | N db hosts → N Deployments of 1 replica, one pod per db host, each on its own host's PV | `ferrosa-kit: One Deployment and one pod for each db host` | ✅ Covered |
| AC Topology | 3+ db hosts → all N pods in one cluster | `ferrosa-kit: Three or more pods form one cluster` | ✅ Covered |
| AC Topology | A restarted pod with a new IP rejoins; others reach it by Service name | `ferrosa-kit: A restarted pod rejoins` | ✅ Covered |
| AC Topology | `start` returns only after every pod is ready on `/readyz` | `ferrosa-kit: Start returns after every pod is ready` | ✅ Covered |
| AC Topology | A pod not ready in the timeout fails `start` naming the pod | `ferrosa-kit: Readiness timeout names the pod` | ✅ Covered |
| AC Topology | No pod on the control node or an app node | `ferrosa-kit: No pod runs off the db nodes` | ✅ Covered |
| AC Storage | Default storage writes to `s3://<data bucket>/ferrosa/`, PV holds only the cache | `ferrosa-kit: Default storage writes to the data bucket` | ✅ Covered |
| AC Storage | `--storage=local` writes to `/var/lib/ferrosa`, nothing to S3 | `ferrosa-kit: Local storage writes nothing to S3` | ✅ Covered |
| AC Storage | Invalid `--storage` fails before any pod, lists the two values | `ferrosa-kit: Invalid storage value is refused` | ✅ Covered |
| AC Storage | S3 unreachable → `start` fails saying S3 access failed, no fallback | `ferrosa-kit: S3 failure fails start without fallback` | ✅ Covered |
| AC Storage | No lifecycle/expiry rule or delete step for `ferrosa/` objects | `ferrosa-kit: No automatic deletion of FerrosaDB data` | ✅ Covered |
| AC Client | Each of the seven listeners answers on its NodePort on every db node's private IP | `ferrosa-kit: Every listener answers on every db node` | ✅ Covered |
| AC Client | `kit info ferrosa` lists seven endpoints; CQL type `cql`, Postgres stub type `postgresql` | `ferrosa-kit: kit info lists the seven endpoints` | ✅ Covered |
| AC Client | No NodePort clashes with another kit or Hubble UI (31234) | `ferrosa-kit: NodePorts are unique` | ✅ Covered |
| AC Client | CQL client creates keyspace and table, writes and reads rows | `ferrosa-kit: CQL round trip through the NodePort` | ✅ Covered |
| AC Client | No pod uses `hostPort` or `hostNetwork` | `ferrosa-kit: No host networking` | ✅ Covered |
| AC Client | CQL client connects with no credentials | `ferrosa-kit: CQL connects with no credentials` | ✅ Covered |
| AC Client | `kit info ferrosa` lists every `start` option | `kit-command-args: Every ferrosa start option listed`, `kit-command-args: Start args listed` | ✅ Covered |
| AC Runtime | `--log-level=debug` reaches every container | `ferrosa-kit: Named options reach every container` | ✅ Covered |
| AC Runtime | `--env KEY1=VALUE1 --env KEY2=VALUE2` reach every container | `ferrosa-kit: Repeated env options all reach every container`, `kit-command-args: Repeated flag accumulates` | ✅ Covered |
| AC Runtime | `--env` wins over a named option | `ferrosa-kit: Env wins over a named option` | ✅ Covered |
| AC Runtime | `--env` per-pod key or non-`KEY=VALUE` line fails before any pod, naming key or line | `ferrosa-kit: Env for a per-pod key is refused`, `ferrosa-kit: Malformed env line is refused`, `ferrosa-kit: A failed validation applies nothing` | ✅ Covered |
| AC Runtime | `--heap-profile` on a profiling build sets `MALLOC_CONF` and `.heap` files appear | `ferrosa-kit: Heap profiling on a profiling build` | ✅ Covered |
| AC Runtime | `--heap-profile --heap-sample=17` → `lg_prof_sample:17` | `ferrosa-kit: Heap sample rate` | ✅ Covered |
| AC Runtime | `--heap-sample` without `--heap-profile` fails before any pod | `ferrosa-kit: Heap sample without heap profile is refused` | ✅ Covered |
| AC Runtime | `--heap-profile` on a non-profiling image fails naming the image | `ferrosa-kit: Heap profiling on a non-profiling build fails start` | ✅ Covered |
| AC Load | A cassandra-easy-stress KeyValue run completes; throughput and latency show on the dashboard | `ferrosa-kit: KeyValue run completes and shows on the dashboard` | ✅ Covered |
| AC Observability | `easydblab-metrics-ferrosa-ferrosa` exists; Mimir has one `instance` per pod, no per-node duplicates | `ferrosa-kit: Metrics registered with one instance for each pod`, `kit-metrics-declaration: Single-target kit creates one ConfigMap` | ✅ Covered |
| AC Observability | `stop` removes the registration and the collector stops scraping | `ferrosa-kit: Stop removes the metrics registration` | ✅ Covered |
| AC Observability | Stdout logs in Loki, filterable to the FerrosaDB pods | `ferrosa-kit: Logs are in Loki` | ✅ Covered |
| AC Observability | Pyroscope has CPU profiles for the pods during a CQL load | `ferrosa-kit: CPU profiles are in Pyroscope` | ✅ Covered |
| AC Observability | Dashboard in Grafana folder `ferrosa`, every panel shows data during the stress run | `ferrosa-kit: Dashboard installed and showing data during a load` | ✅ Covered |
| AC Observability | Every metric name in the dashboard and `METRICS.md` is in `metrics-catalog.json` | `ferrosa-kit: Every metric name is in the catalog` | ✅ Covered (reviewer check, no automated test, by owner decision Q22) |
| AC Observability | A failed metrics registration or dashboard install in any kit's `start` emits a typed event and exits non-zero | `kit-metrics-declaration: Failed metrics registration fails start`, `typed-install-steps: Grafana rejects a dashboard`, `typed-install-steps: Dashboards cannot be read` | ✅ Covered |
| AC Stop | `stop` removes Deployments, pods, Services; PVs, data and `.heap` files remain | `ferrosa-kit: Stop removes the workload and keeps the data` | ✅ Covered |
| AC Stop | `stop` after `--storage=s3` keeps the `ferrosa/` objects | `ferrosa-kit: Stop keeps the S3 data` | ✅ Covered |
| AC Stop | `start` after `stop` succeeds with no manual cleanup | `ferrosa-kit: Start again after stop` | ✅ Covered |
| AC Docs | `docs/user-guide/ferrosa.md` covers every option, ports, CQL, ECR flow, stress, observability, tested topology, mode switching, copying `.heap` files | `ferrosa-kit: User guide covers the kit` | ✅ Covered |
| AC Docs | Scaffolded `README.md` shows connection details for each endpoint | `ferrosa-kit: Scaffolded README shows the endpoints` | ✅ Covered |
| Scope (engine) | Repeatable kit option | `kit-command-args: Repeated flag accumulates`, `Single value of a repeatable arg`, `Repeatable top-level install arg is rejected`, `Repeatable non-string arg is rejected` | ✅ Covered |
| Scope (engine) | Shared arg builder; unset optional arg not recorded as `"null"` | `kit-command-args: Unset optional arg with no default is not recorded`, `Unset boolean arg is false`, `Given boolean arg is true`, `Install args and command args behave the same` | ✅ Covered |
| Scope (engine) | `kit info` lists each command's args | `kit-command-args: Start args listed`, `Repeatable arg is marked` | ✅ Covered |
| Scope (node) | Credential provider on every node; `EcrPullSecretService` removed | `ami-building: Provider installed in the base AMI`, `ami-building: No pull secret is created` | ✅ Covered |
| Scope (engine) | `StubKubectl` moves to its own test file with canned `kubectl get` replies | — | ⚠️ Excluded — test-infrastructure refactor, no product behavior; task 4.1 |
| Risk | E2 changes arg handling for every kit | `kit-command-args: Default value used when flag omitted`, `Install args and command args behave the same` | ✅ Covered (task 1.1 regression tests for sysbench and kafka) |
| Risk | detekt `CyclomaticComplexMethod` on touched methods | — | ⚠️ Excluded — code-quality constraint, not product behavior; tasks 1.5 and 7.6 |
| Risk | Credential-provider config must match ECR hosts; K3s wiring verified on a real node | `ami-building: Provider installed in the base AMI`, `ami-building: A pod pulls from ECR with no pull secret` | ✅ Covered (tasks 5.2, 5.3, 10.1) |
| Risk | A baked `/etc/ferrosa/ferrosa.toml` overrides env; a non-alpine image breaks the `sh` init container | `ferrosa-kit: User guide covers the kit` | ✅ Covered (documented, per the docs requirement) |
| Risk | Resource contention with another database on the db nodes | `ferrosa-kit: User guide covers the kit` | ✅ Covered (documented, per the docs requirement) |
| Risk | Critic 9: dashboard failures were documented as warnings | `typed-install-steps: Dashboards cannot be read`, `typed-install-steps: Grafana rejects a dashboard` | ✅ Covered (task 8.5 updates `commands/CLAUDE.md`) |
