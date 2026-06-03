# Kit Development Guide

This guide covers how to build a kit for easy-db-lab — from the `config.yaml` structure
through lifecycle phases, metrics collection, hooks, and Grafana dashboard provisioning.

## What is a Kit?

A kit is a self-contained package of configuration and scripts that installs, starts, stops,
and optionally backs up a piece of software on the cluster. Examples: ClickHouse, Presto, OpenSearch.

Each kit lives under `src/main/resources/com/rustyrazorblade/easydblab/kits/<name>/`.

After `easy-db-lab kit install <name>` runs, the kit directory is copied to the cluster's working
directory. The `<name> start`, `<name> stop`, etc. subcommands then drive it.

## Directory Layout

```
install/<name>/
├── config.yaml              # Required: kit definition
├── bin/                     # Optional: legacy shell scripts (start.sh, stop.sh, ...)
├── dashboards/              # Optional: Grafana dashboard JSON files
├── <name>.yaml.template     # Optional: K8s manifest templates for typed steps
└── METRICS.md               # Optional but recommended: documents exposed metrics
```

## config.yaml Reference

```yaml
name: myworkload
description: Short description shown in help text
version: "1.0.0"
collision-check: false   # true = refuse to install if already present

metrics:
  type: scrape           # see Metrics section
  port: 9090

runtime:
  type: helm             # see Runtime section
  release: myworkload
  namespace: default

endpoints:
  - name: "HTTP UI"
    node-type: app       # "app" or "db"
    port: 8080
    type: http           # http | https | jdbc | native | cql
    scheme: ""           # optional: used for JDBC URLs
    path: ""             # optional: appended to URL

args:
  - flag: --workers
    variable: WORKERS
    description: "Number of workers"
    type: int            # string | int | float | boolean
    required: false
    default: "${APP_NODE_COUNT}"

hooks:
  post-workload-start:
    script: bin/update-catalogs.sh
    kits: []            # optional: only fire when these kits start
  post-workload-stop:
    script: bin/update-catalogs.sh

install:  []   # steps to run on `easy-db-lab kit install <name>`
start:    []   # steps to run on `easy-db-lab <name> start`
stop:     []   # steps to run on `easy-db-lab <name> stop`
uninstall: []  # steps to run on `easy-db-lab <name> uninstall`
backup:   []   # steps to run on `easy-db-lab <name> backup <backup-name>`
restore:  []   # steps to run on `easy-db-lab <name> restore <backup-name>`
```

## Lifecycle Phases

| Phase | Trigger | What happens after success |
|-------|---------|----------------------------|
| `install` | `easy-db-lab kit install <name>` | Kit directory written to working dir |
| `start` | `easy-db-lab <name> start` | Metrics registered, dashboards installed, hooks fired |
| `stop` | `easy-db-lab <name> stop` | Metrics deregistered, hooks fired |
| `uninstall` | `easy-db-lab <name> uninstall` | Kit directory deleted from working dir |
| `backup` | `easy-db-lab <name> backup <name>` | `BACKUP_NAME` env var set to first argument |
| `restore` | `easy-db-lab <name> restore <name>` | `BACKUP_NAME` env var set to first argument |

If no typed steps are defined for a phase and a matching script exists in `bin/` (e.g.
`bin/start.sh`), the script is executed instead.

## Step Types

All phases use the same set of typed steps.

### `helm-repo`
Adds a Helm chart repository.
```yaml
- type: helm-repo
  name: altinity
  url: https://docs.altinity.com/clickhouse-operator/
```

### `helm`
Installs or upgrades a Helm chart.
```yaml
- type: helm
  chart: altinity/altinity-clickhouse-operator
  release: clickhouse-operator
  namespace: kube-system
  version: "1.2.3"          # optional: pin chart version
  values:                    # optional: inline values
    replicaCount: "3"
  values-file: values.yaml  # optional: path relative to kit dir
```

### `helm-uninstall`
Uninstalls a Helm release.
```yaml
- type: helm-uninstall
  release: clickhouse-operator
  namespace: kube-system
```

### `manifest`
Applies a K8s manifest template. The template file must exist in the kit's resource
directory. Template variables (see below) are substituted before applying.
```yaml
- type: manifest
  template: clickhouseinstallation.yaml
```

### `manifest-url`
Fetches and applies a manifest from a URL.
```yaml
- type: manifest-url
  url: https://example.com/operator.yaml
```

### `kustomize`
Applies a kustomize configuration from a URL.
```yaml
- type: kustomize
  url: https://github.com/example/repo/config/default
```

### `namespace`
Creates a Kubernetes namespace (no-op if it already exists).
```yaml
- type: namespace
  name: monitoring
```

### `wait`
Waits for a K8s resource to reach a condition.
```yaml
- type: wait
  kind: Deployment
  name: my-operator
  namespace: kube-system
  condition: Available   # default: Available
  timeout: 300s          # default: 300s
```

### `delete`
Deletes a K8s resource.
```yaml
- type: delete
  kind: ClickHouseInstallation
  name: clickhouse
  namespace: default
  ignore-not-found: true   # default: true
```

### `platform-pvs`
Creates persistent volumes on cluster nodes using the platform substrate.
```yaml
- type: platform-pvs
  node-type: db    # default: db
  count: 3         # optional: defaults to node count
```

### `configmap`
Creates or updates a K8s ConfigMap.
```yaml
- type: configmap
  name: my-config
  namespace: default
  data:
    key: value
```

### `label`
Applies labels to cluster nodes.
```yaml
- type: label
  node-type: db
  labels:
    kit: presto
```

### `exec`
Runs a command inside a running pod.
```yaml
- type: exec
  pod: my-pod-name
  namespace: default
  command: ["clickhouse-client", "--query", "SELECT 1"]
```

### `shell`
Runs an inline shell script. The script runs locally (not on the remote node) with cluster
variables injected as environment variables.
```yaml
- type: shell
  script: |
    kubectl wait --for=condition=Ready pods \
      -l app=myworkload \
      --timeout=300s
```

## Environment Variables

All scripts and shell steps receive the following environment variables:

| Variable | Description |
|----------|-------------|
| `CLUSTER_NAME` | Name of the cluster |
| `KUBECONFIG` | Absolute path to the local kubeconfig file |
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
| `STORAGE_CLASS_WFC` | K8s storage class name with WaitForFirstConsumer binding |
| `RUNNING_KITS` | Comma-separated names of currently running kits |
| `EASY_DB_LAB_EXEC` | Path to the `easy-db-lab` binary |
| `BACKUP_NAME` | First positional argument (backup and restore phases only) |

Args declared in `config.yaml` under `args:` are also injected using their `variable` name. For
example, `--workers` with `variable: WORKERS` becomes `$WORKERS`.

Default values in `config.yaml` can reference any of the variables above using `${VAR}` syntax:
```yaml
default: "${APP_NODE_COUNT}"
```

## Metrics

The `metrics` field tells easy-db-lab how to collect metrics from the kit. When `start`
succeeds, metrics are registered. When `stop` succeeds, they are deregistered.

### `scrape` — Prometheus endpoint
The kit exposes a Prometheus endpoint. The OTel DaemonSet scrapes it.
```yaml
metrics:
  type: scrape
  port: 9090          # required
  path: /metrics      # optional, default: /metrics
```

Registration creates a K8s ConfigMap named `easydblab-metrics-<kit>` labelled
`easydblab.com/kit-metrics=true`. `OtelSyncService` watches for these ConfigMaps and
regenerates the OTel collector config to add the new scrape job. All scraped metrics receive
`job=<kit>` and `cluster=<cluster-name>` labels automatically.

### `java-agent` — OpenTelemetry Java Agent
For JVM kits. The OTel Java agent JAR at `/usr/local/otel/opentelemetry-javaagent.jar`
is attached to the JVM process.
```yaml
metrics:
  type: java-agent
  service-name: myworkload
```

### `helm-native` — Built-in telemetry
The kit ships its own metrics pipeline via Helm values. No OTel config change is needed.
```yaml
metrics:
  type: helm-native
```

### Documenting Metrics

Every kit that exposes metrics should include a `METRICS.md` file listing the available
metrics, their labels, and usage notes. This is the reference for anyone building dashboards.
See `install/presto/METRICS.md` for an example.

## Hooks

Hooks let one kit react when another kit starts or stops. The hook script runs in the
context of the *declaring* kit, not the triggering one.

```yaml
hooks:
  post-workload-start:
    script: bin/update-catalogs.sh
    kits: [cassandra]   # optional: only fire when cassandra starts
  post-workload-stop:
    script: bin/update-catalogs.sh
```

When `easy-db-lab cassandra start` completes, easy-db-lab scans every installed kit
directory, finds those with a matching `post-workload-start` hook, and fires them.

If `kits` is empty or omitted, the hook fires for any kit start/stop. Hooks retry up
to 3 times with exponential backoff (1s, 2s, 4s) on failure.

**Use case**: Presto registers its Cassandra catalog after Cassandra starts. Its
`post-workload-start` hook runs `bin/update-catalogs.sh` which re-registers catalogs for all
currently running data sources.

## Grafana Dashboards

After a successful `start`, easy-db-lab installs dashboards into Grafana via the HTTP API.

**Auto-discovery** (default): any `.json` files in `dashboards/` are installed automatically.
Files are installed in alphabetical order into a Grafana folder named after the kit.

**Explicit list** (optional): declare dashboard paths in `config.yaml` to control selection or
order:
```yaml
dashboards:
  - path: dashboards/overview.json
  - path: dashboards/queries.json
    name: Query Details
```

Dashboard JSON files should:
- Use `"uid": "<kit>-kit"` to make re-installs idempotent
- Filter by `cluster=~"$cluster"` using a template variable
- Set datasource to `{ "type": "prometheus", "uid": "VictoriaMetrics" }`
- Include `"tags": ["<kit>", "kit"]`

Dashboards are installed with `overwrite: true` so re-running `start` is safe.

## Runtime

The `runtime` field tells easy-db-lab how to find running pods for status checks and log tailing.

```yaml
runtime:
  type: helm          # helm | deployment | statefulset | pods
  release: presto     # for helm: the Helm release name
  namespace: default
  selector: "app=presto"  # for pods: label selector
  name: presto            # for deployment/statefulset: resource name
```

## Profiling

Every kit running on the cluster is automatically profiled at the system level by the Grafana
Alloy eBPF DaemonSet — no per-kit setup required. This covers all processes including
non-JVM ones like ClickHouse.

**JVM kits get deeper profiling** via the Pyroscope Java agent
(`/usr/local/pyroscope/pyroscope.jar`, pre-installed on every node by packer). This enables
method-level CPU, allocation, and lock contention profiles — much richer than eBPF.

### Wiring up the Java agent for a K8s kit

The agent JAR lives on the host at `/usr/local/pyroscope`. Mount it into each JVM container via a
`hostPath` volume, then inject `JAVA_TOOL_OPTIONS` via a `kubectl patch` in the `start` phase.
`CONTROL_HOST_PRIVATE` and `CLUSTER_NAME` are available as environment variables at runtime.

```bash
PYROSCOPE_OPTS="-javaagent:/usr/local/pyroscope/pyroscope.jar \
  -Dpyroscope.application.name=<kit> \
  -Dpyroscope.server.address=http://${CONTROL_HOST_PRIVATE}:4040 \
  -Dpyroscope.format=jfr \
  -Dpyroscope.profiler.event=cpu \
  -Dpyroscope.profiler.alloc=512k \
  -Dpyroscope.profiler.lock=10ms"

PATCH=$(jq -n \
  --arg opts "${PYROSCOPE_OPTS} -Dpyroscope.labels=cluster=${CLUSTER_NAME},component=<component>" \
  '{spec:{template:{spec:{
    volumes:[{name:"pyroscope-agent",hostPath:{path:"/usr/local/pyroscope"}}],
    containers:[{name:"<container-name>",env:[{name:"JAVA_TOOL_OPTIONS",value:$opts}],
      volumeMounts:[{name:"pyroscope-agent",mountPath:"/usr/local/pyroscope",readOnly:true}]}]
  }}}}')
kubectl patch deployment <deployment-name> --namespace default --type=strategic -p="$PATCH"
```

Use `component=coordinator`, `component=worker`, etc. in the `pyroscope.labels` to distinguish
multiple JVM processes belonging to the same kit. Profiles appear in Grafana's Pyroscope
datasource under `service_name=<kit>`.

See `docs/user-guide/profiling.md` for how to access profiles in Grafana, profile types, and
the full observability data flow.

## Adding a New Kit

1. Create `src/main/resources/com/rustyrazorblade/easydblab/kits/<name>/config.yaml`
2. Add lifecycle steps — start with `start` and `stop` at minimum
3. Add a `metrics` block if the kit exposes Prometheus metrics
4. Add a `METRICS.md` documenting the available metrics
5. Create `dashboards/<name>.json` with panels for the key metrics
6. Run `easy-db-lab kit install <name>` to scaffold the working directory
7. Test `<name> start` and `<name> stop` against a real cluster

No Kotlin code is required. The install and kit runner commands register dynamically from
the `config.yaml` files at startup.
