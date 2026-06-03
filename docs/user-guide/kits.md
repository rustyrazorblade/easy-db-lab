# Kits

A kit is a self-contained package of configuration and scripts that installs, starts, stops,
and optionally backs up a workload on your cluster. Each kit defines its full lifecycle in a
`kit.yaml` file using typed steps — no Kubernetes YAML wrangling required.

easy-db-lab ships with built-in kits (ClickHouse, Presto). You can also create your own kits
for any workload you want to benchmark or test.

## Discovering kits

List all available kits:

```bash
$EDB kit list
```

Inspect a kit before installing it — see its args, endpoints, and available commands:

```bash
$EDB kit info clickhouse
```

## Installing a kit

```bash
$EDB kit install clickhouse --clickhouse-version 25.4 --size 100Gi
```

Args vary by kit. Run `kit info <name>` to see what a kit accepts, or pass `--help`:

```bash
$EDB kit install clickhouse --help
```

After install, the kit's files are written into a subdirectory of the cluster workspace.
The kit's lifecycle commands are registered automatically.

## Running kit commands

Every installed kit gains a set of subcommands:

```bash
$EDB clickhouse start       # deploy and start the workload
$EDB clickhouse status      # show running state and connection endpoints
$EDB clickhouse stop        # stop and remove the workload
$EDB clickhouse backup --name my-backup   # back up data
$EDB clickhouse restore --name my-backup  # restore from backup
$EDB clickhouse uninstall   # stop and remove all kit resources
```

## Creating a custom kit

You can create a kit for any workload — a custom stress tool, a third-party database, a
sidecar service. No Kotlin required. A kit is just a directory with a `kit.yaml` and optional
template files.

### Directory layout

```
my-kit/
├── kit.yaml                     # Required: kit definition
├── bin/                         # Optional: shell scripts for script-based phases
│   ├── start.sh.template
│   ├── stop.sh.template
│   └── update-catalogs.sh.template
├── my-manifest.yaml.template    # Optional: K8s manifest templates
├── dashboards/                  # Optional: Grafana dashboard JSON files
│   └── overview.json
└── METRICS.md                   # Recommended: documents exposed metrics
```

Files ending in `.template` are rendered by the kit installer with cluster variables
substituted (see [Environment variables](#environment-variables) below).

### kit.yaml reference

```yaml
name: my-kit
type: db           # "db" (targets database nodes) or "app" (targets app nodes)
description: Short description shown in help text
version: "1.0.0"
collision-check: true   # true = refuse to install if this kit is already installed

metrics:           # optional: how easy-db-lab collects metrics from this kit
  type: scrape
  port: 9090
  path: /metrics   # default: /metrics

runtime:           # optional: how easy-db-lab finds running pods for status/logs
  type: helm       # helm | deployment | statefulset | pods
  release: my-kit
  namespace: default

endpoints:         # optional: connection info shown by `status`
  - name: "HTTP UI"
    node-type: db  # "db" or "app"
    port: 8080
    type: http     # http | https | jdbc | native | cql
    path: /ui      # optional path appended to URLs

args:              # optional: flags exposed on `kit install <name>`
  - flag: --workers
    variable: WORKERS
    description: "Number of workers"
    type: int      # string | int | float | boolean
    required: false
    default: "${APP_NODE_COUNT}"

hooks:             # optional: react when another kit starts or stops
  post-workload-start:
    script: bin/update-catalogs.sh
    workloads: [cassandra]    # optional: only fire when cassandra starts
  post-workload-stop:
    script: bin/update-catalogs.sh

install:  []   # steps run on `kit install <name>`
start:    []   # steps run on `<kit> start`
stop:     []   # steps run on `<kit> stop`
uninstall: []  # steps run on `<kit> uninstall`
backup:   []   # steps run on `<kit> backup --name <backup-name>`
restore:  []   # steps run on `<kit> restore --name <backup-name>`
```

### Lifecycle phases

| Phase | Triggered by | Notes |
|-------|-------------|-------|
| `install` | `kit install <name>` | Run once to install operators, chart repos, etc. |
| `start` | `<kit> start` | Metrics registered, dashboards installed, hooks fired after success |
| `stop` | `<kit> stop` | Metrics deregistered, hooks fired after success |
| `uninstall` | `<kit> uninstall` | Tear down all resources installed by this kit |
| `backup` | `<kit> backup --name <n>` | `BACKUP_NAME` env var is set to the name argument |
| `restore` | `<kit> restore --name <n>` | `BACKUP_NAME` env var is set to the name argument |

If no typed steps are defined for a phase and a matching script exists in `bin/` (e.g.
`bin/start.sh`), the script is executed instead.

### Step types

All lifecycle phases use the same set of step types.

**`helm-repo`** — add a Helm chart repository:
```yaml
- type: helm-repo
  name: my-repo
  url: https://charts.example.com/
```

**`helm`** — install or upgrade a Helm chart:
```yaml
- type: helm
  chart: my-repo/my-chart
  release: my-kit
  namespace: default
  version: "1.2.3"       # optional: pin chart version
  values:                 # optional: inline values
    replicaCount: "3"
  values-file: values.yaml   # optional: path relative to kit dir
```

**`helm-uninstall`** — uninstall a Helm release:
```yaml
- type: helm-uninstall
  release: my-kit
  namespace: default
```

**`manifest`** — apply a K8s manifest template from the kit directory:
```yaml
- type: manifest
  template: my-manifest.yaml
```

**`manifest-url`** — fetch and apply a manifest from a URL:
```yaml
- type: manifest-url
  url: https://example.com/operator.yaml
```

**`kustomize`** — apply a kustomize configuration from a URL:
```yaml
- type: kustomize
  url: https://github.com/example/repo/config/default
```

**`namespace`** — create a Kubernetes namespace (no-op if it already exists):
```yaml
- type: namespace
  name: my-namespace
```

**`wait`** — wait for a K8s resource to reach a condition:
```yaml
- type: wait
  kind: Deployment
  name: my-operator
  namespace: default
  condition: Available   # default: Available
  timeout: 300s          # default: 300s
```

**`delete`** — delete a K8s resource:
```yaml
- type: delete
  kind: Deployment
  name: my-operator
  namespace: default
  ignore-not-found: true   # default: true
```

**`platform-pvs`** — create persistent volumes on cluster nodes:
```yaml
- type: platform-pvs
  node-type: db    # default: db
  count: 3         # optional: defaults to node count
```

**`configmap`** — create or update a K8s ConfigMap:
```yaml
- type: configmap
  name: my-config
  namespace: default
  data:
    key: value
```

**`label`** — apply labels to cluster nodes:
```yaml
- type: label
  node-type: db
  labels:
    kit: my-kit
```

**`exec`** — run a command inside a running pod:
```yaml
- type: exec
  pod: my-pod-name
  namespace: default
  command: ["my-cli", "--query", "SELECT 1"]
```

**`shell`** — run an inline shell script with cluster variables available as environment variables:
```yaml
- type: shell
  script: |
    kubectl wait --for=condition=Ready pods \
      -l app=my-kit \
      --timeout=300s
```

### Environment variables

All scripts and shell steps receive these environment variables automatically:

| Variable | Description |
|----------|-------------|
| `CLUSTER_NAME` | Name of the cluster |
| `KUBECONFIG` | Path to the local kubeconfig |
| `CONTROL_HOST` | Public IP of the control node |
| `CONTROL_HOST_PUBLIC` | Public IP of the control node |
| `CONTROL_HOST_PRIVATE` | Private IP of the control node |
| `DB_NODE_COUNT` | Number of database nodes |
| `APP_NODE_COUNT` | Number of app/stress nodes |
| `DB_NODE_IPS` | Comma-separated private IPs of database nodes |
| `APP_NODE_IPS` | Comma-separated private IPs of app nodes |
| `BUCKET_NAME` | S3 data bucket name |
| `ACCOUNT_BUCKET` | S3 account-level bucket name |
| `REGION` | AWS region |
| `KIT_NAME` | Name of this kit |
| `STORAGE_SIZE` | Storage size (from `--size` arg, if used) |
| `STORAGE_CLASS_WFC` | K8s storage class with WaitForFirstConsumer binding |
| `RUNNING_KITS` | Comma-separated names of currently running kits |
| `EASY_DB_LAB_EXEC` | Path to the `easy-db-lab` binary |
| `BACKUP_NAME` | Name argument (backup and restore phases only) |

Args declared in `kit.yaml` under `args:` are also injected using their `variable` name. For
example, `--workers` with `variable: WORKERS` becomes `$WORKERS`.

Default values in `args:` can reference any of the variables above:
```yaml
default: "${APP_NODE_COUNT}"
```

### Metrics

Declare how easy-db-lab should collect metrics from your kit. When `start` succeeds, the metrics
pipeline is registered automatically; when `stop` succeeds, it is deregistered.

**`scrape`** — kit exposes a Prometheus endpoint:
```yaml
metrics:
  type: scrape
  port: 9090
  path: /metrics   # default: /metrics
```

**`java-agent`** — JVM kit using the OTel Java agent:
```yaml
metrics:
  type: java-agent
  service-name: my-kit
```

**`helm-native`** — kit has built-in telemetry via Helm values:
```yaml
metrics:
  type: helm-native
```

Include a `METRICS.md` in your kit directory listing the available metrics, their labels, and
intended use. This is the reference for anyone building dashboards.

### Hooks

Hooks let your kit react when another kit starts or stops. The hook script runs with the full set
of cluster environment variables.

```yaml
hooks:
  post-workload-start:
    script: bin/update-catalogs.sh
    workloads: [cassandra]   # optional: only fire when cassandra starts
  post-workload-stop:
    script: bin/update-catalogs.sh
```

If `workloads` is empty or omitted, the hook fires for any kit start/stop. Hooks are retried up
to 3 times with exponential backoff on failure.

**Example use case**: Presto registers its Cassandra catalog after Cassandra starts. When
`easy-db-lab cassandra start` completes, the `post-workload-start` hook re-runs catalog
registration.

### Grafana dashboards

After a successful `start`, easy-db-lab installs your dashboards into Grafana automatically.

Place `.json` dashboard files in `dashboards/` — they are installed in alphabetical order into a
Grafana folder named after your kit.

For best results, dashboard JSON should:
- Set `"uid": "<kit>-kit"` for idempotent re-installs
- Filter by `cluster=~"$cluster"` using a template variable
- Set datasource to `{ "type": "prometheus", "uid": "VictoriaMetrics" }`
- Include `"tags": ["<kit>", "kit"]`

To control dashboard selection or order explicitly:
```yaml
dashboards:
  - path: dashboards/overview.json
  - path: dashboards/queries.json
    name: Query Details
```

## Installing a custom kit

Place your kit directory under the profile kits folder and it will appear in `kit list` and be
installable by name like any built-in kit:

```
~/.easy-db-lab/profiles/default/kits/<kit-name>/
```

Custom kits in the profile directory take precedence over built-in kits with the same name.

```bash
mkdir -p ~/.easy-db-lab/profiles/default/kits/my-kit
cp -r /path/to/my-kit/* ~/.easy-db-lab/profiles/default/kits/my-kit/

# Now it appears in kit list and can be installed by name:
$EDB kit install my-kit
```
