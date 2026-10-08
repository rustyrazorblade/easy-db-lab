## ADDED Requirements

### Requirement: FerrosaDB kit runs one pod on each db host
The system SHALL provide a built-in kit named `ferrosa` (`type: db`) that runs FerrosaDB on the db nodes. `install` SHALL create one platform PV for each db host with `platform-pvs` (`data-ferrosa-<i>`, pinned to db ordinal `i`). `start` SHALL create one Deployment (`ferrosa-<i>`, `replicas: 1`, strategy `Recreate`) and one PVC (`ferrosa-data-<i>`, bound by `volumeName: data-ferrosa-<i>`) for each db host `i` in `0..DB_NODE_COUNT-1`, so the pod count always equals the db host count. The kit SHALL have no count or replicas option and SHALL NOT use a StatefulSet. Each pod SHALL require node affinity `type In [db]` and SHALL NOT use `hostPort` or `hostNetwork`. Each pod SHALL carry the labels `easydblab/kit=ferrosa`, `app.kubernetes.io/name=ferrosa`, `app.kubernetes.io/instance=ferrosa` and `easydblab/ferrosa-ordinal=<i>`. `kit.yaml` SHALL set `collision-check: true` and a `runtime` pods selector of `easydblab/kit=ferrosa`.

#### Scenario: One Deployment and one pod for each db host
- **WHEN** the cluster has N db hosts and the owner runs `easy-db-lab ferrosa start`
- **THEN** there are N Deployments named `ferrosa-0` to `ferrosa-<N-1>`, each with 1 replica
- **AND** exactly one FerrosaDB pod runs on each db host
- **AND** pod `i` mounts the PVC bound to `data-ferrosa-<i>`, the platform PV of db host `i`

#### Scenario: No pod runs off the db nodes
- **WHEN** the kit runs on a cluster with a control node and app nodes
- **THEN** no FerrosaDB pod is scheduled on the control node or on an app node

#### Scenario: No host networking
- **WHEN** the rendered FerrosaDB manifests are inspected
- **THEN** no container declares a `hostPort` and no pod sets `hostNetwork: true`

#### Scenario: Data lands on the node's data disk, not the root volume
- **WHEN** `start` completes and FerrosaDB has written rows
- **THEN** in each pod, `/var/lib/ferrosa` is the mount of the PVC `ferrosa-data-<i>`, not the container's own file system
- **AND** on each db node, `/mnt/db1/ferrosa` holds FerrosaDB's data files and heap-profile directory, and `findmnt -T /mnt/db1/ferrosa` reports the data-disk device, not the root volume's device

#### Scenario: A second start on a running kit is refused
- **WHEN** FerrosaDB is running and the owner runs `easy-db-lab ferrosa start` again
- **THEN** `start` fails with a `Kit.CollisionDetected` event before any start step runs

### Requirement: FerrosaDB image is selected by tag or by full reference
The default image SHALL be `ghcr.io/ferrosadb/ferrosa:nightly`. The `--version` start option (variable `FERROSA_TAG`, no `kit.yaml` default) SHALL select the tag of that image; when it is not given, the tag SHALL be `nightly`. The `--image` start option (variable `IMAGE`) SHALL replace the whole image reference. Giving both `--image` and `--version` SHALL fail `start` before it applies anything, with an error that says to use one of them. Every FerrosaDB container, including the init container, SHALL use `imagePullPolicy: Always`. The kit SHALL NOT create or reference an image pull secret; an image in the account's ECR SHALL pull through the node's kubelet ECR credential provider.

#### Scenario: Default image
- **WHEN** the owner runs `easy-db-lab ferrosa start` without `--version` or `--image`
- **THEN** every FerrosaDB pod runs `ghcr.io/ferrosadb/ferrosa:nightly`

#### Scenario: Version selects the tag
- **WHEN** the owner runs `easy-db-lab ferrosa start --version=<tag>`
- **THEN** every FerrosaDB pod runs `ghcr.io/ferrosadb/ferrosa:<tag>`

#### Scenario: Custom ECR image pulls with no credential step
- **WHEN** the owner runs `easy-db-lab ferrosa start --image=<account>.dkr.ecr.<region>.amazonaws.com/<repo>:<tag>`
- **THEN** every FerrosaDB pod runs that image
- **AND** the pull succeeds with no pull secret and no manual credential step

#### Scenario: Image and version together are refused
- **WHEN** the owner gives both `--image` and `--version`
- **THEN** `start` fails before it creates any Kubernetes object, with an error that says to use one of `--image` or `--version`

#### Scenario: Each start pulls the image again
- **WHEN** the rendered Deployments are inspected
- **THEN** every FerrosaDB container and init container has `imagePullPolicy: Always`
- **AND** no pod spec has `imagePullSecrets`

### Requirement: FerrosaDB pods form one ring with stable addresses
Each pod `i` SHALL get its own ClusterIP Service `ferrosa-<i>` that selects only that pod, exposes ports 17000 and 9042, and sets `publishNotReadyAddresses: true`. Each container SHALL set:
- `FERROSA_INTERNODE_BROADCAST=ferrosa-<i>.default.svc.cluster.local:17000`
- `FERROSA_SEED` to the Service names of all the other pods, each with `:17000`, comma-joined (unset when there is one db host)
- `FERROSA_HOST_ID=00000000-0000-0000-0000-<12 hex digits of i+1>`, so host ids sort in host order
- `FERROSA_CQL_BROADCAST=$(POD_IP):9042` and `FERROSA_FLIGHT_BROADCAST=$(POD_IP):8815`, with `POD_IP` from `status.podIP`
- `FERROSA_CLUSTER_NAME` from the `cluster_name` key of the `cluster-config` ConfigMap (`<name>-<id>`), the same on every pod
- `FERROSA_EXPECTED_CLUSTER_SIZE=<N>` on every pod when the db host count N is 3 or more, and unset otherwise

Every client listener SHALL bind to `0.0.0.0` (the FerrosaDB default is `127.0.0.1`), and internode traffic on 17000 SHALL have no NodePort. The kit SHALL NOT refuse 1 or 2 db hosts; only 3 or more is the tested topology.

#### Scenario: Three or more pods form one cluster
- **WHEN** the cluster has 3 or more db hosts and `start` completes
- **THEN** all N pods are members of one FerrosaDB cluster
- **AND** every pod has `FERROSA_EXPECTED_CLUSTER_SIZE=<N>`

#### Scenario: Seeds and broadcast use the per-pod Service names
- **WHEN** the start step renders the manifests for 3 db hosts
- **THEN** pod `ferrosa-1` has `FERROSA_INTERNODE_BROADCAST=ferrosa-1.default.svc.cluster.local:17000`
- **AND** its `FERROSA_SEED` lists the Services of `ferrosa-0` and `ferrosa-2` with port 17000, and not its own
- **AND** each Service `ferrosa-<i>` sets `publishNotReadyAddresses: true`

#### Scenario: Host ids are UUIDs in host order
- **WHEN** the start step renders the manifests for N db hosts
- **THEN** pod `i` has `FERROSA_HOST_ID` equal to `00000000-0000-0000-0000-` followed by `i+1` as 12 hex digits
- **AND** sorting the host ids gives the pods in host order

#### Scenario: Expected cluster size is unset below three hosts
- **WHEN** the start step renders the manifests for 1 or 2 db hosts
- **THEN** no container sets `FERROSA_EXPECTED_CLUSTER_SIZE`
- **AND** `start` is not refused because of the host count

#### Scenario: A restarted pod rejoins
- **WHEN** any one FerrosaDB pod restarts and gets a new pod IP
- **THEN** it joins the cluster again with no manual step
- **AND** the other pods reach it by its Service name

#### Scenario: Listeners bind to every interface
- **WHEN** the `ferrosa-settings` ConfigMap is inspected
- **THEN** the CQL, web, Postgres, graph, SPARQL, Flight and internode binds use `0.0.0.0`

### Requirement: FerrosaDB storage mode is S3 or local
The `--storage` start option (variable `STORAGE_MODE`) SHALL accept `local` (the default) or `s3`. Both modes SHALL use `FERROSA_DATA_DIR=/var/lib/ferrosa` on the platform PV. In `s3` mode the kit SHALL set `FERROSA_S3_ENDPOINT=https://s3.<region>.amazonaws.com`, `FERROSA_S3_REGION=<region>`, `FERROSA_S3_BUCKET=<cluster data bucket>`, `FERROSA_S3_PREFIX=ferrosa/` and `FERROSA_S3_REQUIRED=true`, with no access key, so FerrosaDB uses the node instance profile and never falls back to local storage. In `local` mode the kit SHALL set none of the `FERROSA_S3_*` variables. Any other value SHALL fail `start` before it applies anything, with an error that lists `local` and `s3`. The kit SHALL NOT add a lifecycle rule, an expiry rule, or a delete step for the objects under `ferrosa/` in the data bucket, in any phase. Switching modes on an existing PV is not supported, and the kit SHALL NOT check for it.

#### Scenario: Default storage is local and writes nothing to S3
- **WHEN** the owner runs `easy-db-lab ferrosa start` without `--storage` and writes rows
- **THEN** FerrosaDB writes its data to `/var/lib/ferrosa` on the local PV
- **AND** no `FERROSA_S3_*` variable is set and nothing is written under `ferrosa/` in the data bucket

#### Scenario: S3 storage writes to the data bucket
- **WHEN** the owner runs `easy-db-lab ferrosa start --storage=s3` and writes rows
- **THEN** FerrosaDB writes its data under `ferrosa/` in the cluster data bucket
- **AND** the local PV holds only the cache

#### Scenario: Invalid storage value is refused
- **WHEN** the owner gives `--storage` a value other than `local` or `s3`
- **THEN** `start` fails before it creates any Kubernetes object, with an error that lists `local` and `s3`

#### Scenario: S3 failure fails start without fallback
- **WHEN** `--storage=s3` and FerrosaDB cannot reach S3
- **THEN** `start` fails with an error that shows the FerrosaDB "S3 access failed" message
- **AND** FerrosaDB does not fall back to local storage

#### Scenario: No automatic deletion of FerrosaDB data
- **WHEN** the kit is installed, started, stopped, or uninstalled
- **THEN** no lifecycle rule, expiry rule, or delete step targets the objects under `ferrosa/` in the data bucket

### Requirement: FerrosaDB client listeners are exposed through fixed NodePorts
The kit SHALL expose seven client listeners through NodePort Services on fixed ports that no other kit and not Hubble UI (31234) use: CQL 9042 on 30942, console with `/metrics` and `/readyz` 9090 on 30909, Bolt 7687 on 30787, graph HTTP 7474 on 30747, SPARQL 8080 on 30880, Postgres stub 5432 on 30532, and Arrow Flight 8815 on 30815. Every NodePort Service SHALL select only the pod with `easydblab/ferrosa-ordinal=0`. `kit.yaml` SHALL declare each as an endpoint with `node-type: db`: CQL type `cql`, console type `http` with path `/`, Bolt type `native`, graph HTTP type `http`, SPARQL type `http`, Postgres stub type `postgresql`, Flight type `native`.

#### Scenario: Every listener answers on every db node
- **WHEN** `start` completes
- **THEN** each of the seven client listeners answers on its NodePort on the private IP of every db node

#### Scenario: kit info lists the seven endpoints
- **WHEN** the owner runs `easy-db-lab kit info ferrosa` after `start`
- **THEN** it lists the seven endpoints
- **AND** the CQL endpoint has the type `cql` and the Postgres stub endpoint has the type `postgresql`

#### Scenario: NodePorts are unique
- **WHEN** the NodePorts of this kit are compared to the NodePorts of every other built-in kit and to Hubble UI (31234)
- **THEN** no port is the same

#### Scenario: NodePorts select the first pod
- **WHEN** the NodePort Services are inspected
- **THEN** each one selects `easydblab/ferrosa-ordinal=0`

#### Scenario: CQL round trip through the NodePort
- **WHEN** a CQL client connects to the CQL NodePort
- **THEN** it can create a keyspace and a table, write rows, and read the rows back

### Requirement: FerrosaDB runs without authentication
The kit SHALL run FerrosaDB with authentication off (the upstream default) and SHALL have no `--auth` option.

#### Scenario: CQL connects with no credentials
- **WHEN** `start` completes and a CQL client connects to the CQL NodePort with no credentials
- **THEN** the connection is accepted

### Requirement: FerrosaDB runtime settings are start options
The kit SHALL declare these `start` options under `commands: start: args:`:
- `--log-level` (variable `LOG_LEVEL`, default `info`) sets `RUST_LOG`
- `--env KEY=VALUE` (variable `EXTRA_ENV`, `repeatable: true`) passes any other setting to every FerrosaDB container

The shared settings SHALL live in a `ferrosa-settings` ConfigMap and the `--env` lines in a `ferrosa-env` ConfigMap, both labelled `easydblab/kit=ferrosa` and read by `envFrom` in the order `[ferrosa-settings, ferrosa-env]`, so `--env` wins over a named option. An `--env` key that the kit sets per pod (`FERROSA_HOST_ID`, `FERROSA_SEED`, `FERROSA_INTERNODE_BROADCAST`, `FERROSA_CQL_BROADCAST`, `FERROSA_FLIGHT_BROADCAST`, `FERROSA_CLUSTER_NAME`, `FERROSA_EXPECTED_CLUSTER_SIZE`) SHALL fail `start` with an error that names the key, and an `--env` line that is not `KEY=VALUE` SHALL fail `start` with an error that names the line. Every validation check of `start` SHALL run before the kit applies anything.

#### Scenario: Named options reach every container
- **WHEN** the owner runs `easy-db-lab ferrosa start --log-level=debug`
- **THEN** every FerrosaDB container has `RUST_LOG=debug`

#### Scenario: Repeated env options all reach every container
- **WHEN** the owner runs `easy-db-lab ferrosa start --env KEY1=VALUE1 --env KEY2=VALUE2`
- **THEN** every FerrosaDB container has `KEY1=VALUE1` and `KEY2=VALUE2`

#### Scenario: Env wins over a named option
- **WHEN** the owner runs `easy-db-lab ferrosa start --log-level=debug --env RUST_LOG=trace`
- **THEN** every FerrosaDB container has `RUST_LOG=trace`

#### Scenario: Env for a per-pod key is refused
- **WHEN** the owner runs `easy-db-lab ferrosa start --env FERROSA_SEED=x:17000`
- **THEN** `start` fails before it creates any Kubernetes object, with an error that names `FERROSA_SEED`

#### Scenario: Malformed env line is refused
- **WHEN** the owner runs `easy-db-lab ferrosa start --env NOEQUALS`
- **THEN** `start` fails before it creates any Kubernetes object, with an error that names `NOEQUALS`

#### Scenario: A failed validation applies nothing
- **WHEN** any `start` validation check fails
- **THEN** no `kubectl` command that creates or changes an object has run

### Requirement: FerrosaDB heap profiling on a profiling build
The `--heap-profile` start option (variable `HEAP_PROFILE`, boolean) SHALL set `MALLOC_CONF=prof:true,prof_active:true,prof_final:true,prof_prefix:/var/lib/ferrosa/heap-profiles/ferrosa,lg_prof_sample:<n>` in the `ferrosa-settings` ConfigMap, where `<n>` is the `--heap-sample` option (variable `HEAP_SAMPLE`, default `19`). An init container from the same image, running as root, SHALL create the data and heap-profile directories and chown them (non-recursively) to `10001:101`. `--heap-sample` without `--heap-profile` SHALL fail `start` before it applies anything. After the pods are ready, when `--heap-profile` is set, `start` SHALL search each pod's log for `Invalid conf pair: prof` and, if found, SHALL fail with an error that says `--heap-profile` needs a FerrosaDB profiling build and names the image.

#### Scenario: Heap profiling on a profiling build
- **WHEN** the owner runs `easy-db-lab ferrosa start --heap-profile` with a profiling build
- **THEN** every FerrosaDB container has `MALLOC_CONF` containing `prof:true,prof_active:true,prof_final:true` and `prof_prefix:/var/lib/ferrosa/heap-profiles/ferrosa`
- **AND** `.heap` files appear in `/var/lib/ferrosa/heap-profiles/` on the platform PV

#### Scenario: Heap sample rate
- **WHEN** the owner runs `easy-db-lab ferrosa start --heap-profile --heap-sample=17`
- **THEN** `MALLOC_CONF` contains `lg_prof_sample:17`

#### Scenario: Heap sample without heap profile is refused
- **WHEN** the owner runs `easy-db-lab ferrosa start --heap-sample=17` without `--heap-profile`
- **THEN** `start` fails before it creates any Kubernetes object

#### Scenario: Heap profiling on a non-profiling build fails start
- **WHEN** the owner runs `easy-db-lab ferrosa start --heap-profile` with an image that is not a profiling build
- **THEN** `start` fails with an error that says `--heap-profile` needs a profiling build and names the image

### Requirement: FerrosaDB start waits for readiness and reports failures
Each FerrosaDB container SHALL have a readiness probe `httpGet /readyz` on port 9090 and `terminationGracePeriodSeconds: 90`, longer than FerrosaDB's 30-second drain. `start` SHALL return only after every FerrosaDB pod reports ready, polling every 5 seconds up to a default timeout of 600 seconds. It SHALL read both the init container and the main container status. On `ErrImagePull`, `ImagePullBackOff` or `InvalidImageName` it SHALL fail at once with an error that names the pod and the full image reference. On `CrashLoopBackOff` or a failed init container it SHALL fail and print the tail of the previous container's log. On timeout it SHALL fail with an error that names every pod that is not ready.

#### Scenario: Start returns after every pod is ready
- **WHEN** `start` completes
- **THEN** every FerrosaDB pod answered ready on `/readyz` before `start` returned

#### Scenario: Missing image or tag fails start
- **WHEN** the image or tag does not exist
- **THEN** `start` fails with an error that names the pod and the full image reference including the tag
- **AND** `start` does not report success

#### Scenario: A crash shows the FerrosaDB error
- **WHEN** a FerrosaDB container crashes during `start`
- **THEN** `start` fails and prints the tail of that container's previous log

#### Scenario: Readiness timeout names the pod
- **WHEN** a pod does not become ready within the timeout
- **THEN** `start` fails with an error that names that pod

#### Scenario: Drain is not cut short
- **WHEN** the rendered Deployments are inspected
- **THEN** each pod has `terminationGracePeriodSeconds: 90`

### Requirement: FerrosaDB stop keeps the data
`stop` SHALL delete the kit's Deployments, ReplicaSets, pods, Services and ConfigMaps by the selector `easydblab/kit=ferrosa`, and SHALL keep the PVCs, the platform PVs and their data, including the `.heap` files, and the objects under `ferrosa/` in the data bucket. `uninstall` SHALL do the same, delete the PVCs, and then run `platform-pvs-delete`. `start` after `stop` SHALL succeed with no manual cleanup.

#### Scenario: Stop removes the workload and keeps the data
- **WHEN** the owner runs `easy-db-lab ferrosa stop`
- **THEN** no FerrosaDB Deployment, pod, or Service remains
- **AND** the platform PVs and the data on them, including the `.heap` files, are still there

#### Scenario: Stop keeps the S3 data
- **WHEN** the owner runs `easy-db-lab ferrosa stop` after a start with `--storage=s3`
- **THEN** the objects under `ferrosa/` in the data bucket are still there

#### Scenario: Start again after stop
- **WHEN** the owner runs `easy-db-lab ferrosa start` after `stop`
- **THEN** the start succeeds with no manual cleanup

#### Scenario: Uninstall removes the claims and PVs
- **WHEN** the owner runs `easy-db-lab ferrosa uninstall`
- **THEN** no PVC labelled `easydblab/kit=ferrosa` remains and the kit's platform PVs are deleted

### Requirement: FerrosaDB metrics, logs and profiles reach the observability stack
`kit.yaml` SHALL declare one `scrape` metrics entry with job `ferrosa`, port 9090, path `/metrics` and `pod-selector: app.kubernetes.io/name=ferrosa`, so `start` registers the ConfigMap `easydblab-metrics-ferrosa-ferrosa` and every pod is scraped once by the collector on its own node. The kit SHALL set no `cluster` label and SHALL NOT use the bare `CLUSTER_NAME`; the collector adds `cluster`. The pods' stdout logs SHALL reach Loki through the existing container log collection, and CPU profiles SHALL reach Pyroscope through the existing Alloy eBPF profiler, with no kit change. The kit SHALL configure no traces exporter.

#### Scenario: Metrics registered with one instance for each pod
- **WHEN** `start` succeeds
- **THEN** the `easydblab-metrics-ferrosa-ferrosa` ConfigMap exists
- **AND** Mimir has FerrosaDB series with one `instance` for each pod and no duplicate series for each node

#### Scenario: Stop removes the metrics registration
- **WHEN** `stop` succeeds
- **THEN** the `easydblab-metrics-ferrosa-ferrosa` ConfigMap is gone
- **AND** the OTel collector configuration no longer has a `ferrosa` scrape job

#### Scenario: Logs are in Loki
- **WHEN** the FerrosaDB pods write logs to stdout
- **THEN** the logs are in Loki and can be filtered to the FerrosaDB pods

#### Scenario: CPU profiles are in Pyroscope
- **WHEN** a CQL load runs against FerrosaDB
- **THEN** Pyroscope has CPU profiles for the FerrosaDB pods

### Requirement: FerrosaDB dashboard and metrics catalog
The kit directory SHALL contain `metrics-catalog.json` exported with `bin/export-workload-metrics ferrosa` from a running FerrosaDB cluster, a `METRICS.md` written from that catalog, and `dashboards/ferrosa.json`. `start` SHALL install the dashboard into a Grafana folder named `ferrosa`. The dashboard SHALL name the datasources only through the `metrics_datasource` and `logs_datasource` pickers, and every metric name in the dashboard and in `METRICS.md` SHALL be in `metrics-catalog.json`. A reviewer checks the names; there is no automated catalog test.

#### Scenario: Dashboard installed and showing data during a load
- **WHEN** `start` succeeds and a cassandra-easy-stress run is going against FerrosaDB
- **THEN** the FerrosaDB dashboard is in a Grafana folder named `ferrosa`
- **AND** every panel shows data

#### Scenario: Every metric name is in the catalog
- **WHEN** a reviewer compares each metric name in the dashboard and in `METRICS.md` to `metrics-catalog.json`
- **THEN** every name is in the catalog

### Requirement: cassandra-easy-stress runs against FerrosaDB
A cassandra-easy-stress workload SHALL run against FerrosaDB through the existing `cassandra stress start` command with `--host ferrosa-0.default.svc.cluster.local`, with no code change. The docs SHALL give the exact command, including the replication argument settled in the real-cluster run.

#### Scenario: KeyValue run completes and shows on the dashboard
- **WHEN** the owner runs one cassandra-easy-stress workload (for example `KeyValue`) as a container against the FerrosaDB CQL endpoint
- **THEN** the run completes without errors
- **AND** the throughput and latency for the run show on the FerrosaDB dashboard

### Requirement: FerrosaDB kit is documented
The kit SHALL ship a `README.md.template` with the connection details for each endpoint. `docs/user-guide/ferrosa.md`, linked from `docs/SUMMARY.md`, SHALL show every start option (`--version`, `--image`, `--storage`, `--log-level`, `--heap-profile`, `--heap-sample`, `--env`), the seven NodePorts, how to connect with CQL, how to push a custom build to ECR and run it, how to run cassandra-easy-stress against FerrosaDB, where the metrics, logs, profiles and dashboard are, that only 3 or more db hosts is tested, that switching `--storage` modes is not supported, how to copy the `.heap` files off before `down`, that a custom image that bakes `/etc/ferrosa/ferrosa.toml` overrides the kit's env settings, that a custom image needs `sh` for the init container, and that running another database on the same db nodes contends for their resources.

#### Scenario: User guide covers the kit
- **WHEN** the owner opens `docs/user-guide/ferrosa.md`
- **THEN** it shows every start option, the seven NodePorts, CQL connection, the ECR custom-build flow, the cassandra-easy-stress command, where the metrics, logs, profiles and dashboard are, the tested topology, the unsupported storage-mode switch, and how to copy the `.heap` files off before `down`

#### Scenario: Scaffolded README shows the endpoints
- **WHEN** the kit is scaffolded
- **THEN** its `README.md` shows the connection details for each endpoint

#### Scenario: kit list shows ferrosa
- **WHEN** the owner runs `easy-db-lab kit list`
- **THEN** `ferrosa` appears in the output
