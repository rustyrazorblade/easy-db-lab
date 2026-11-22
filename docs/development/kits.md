# Kit Development Guide

This guide covers how to build a kit for easy-db-lab — from the `kit.yaml` structure
through lifecycle phases, metrics collection, hooks, and Grafana dashboard provisioning.

## What is a Kit?

A kit is a self-contained package of configuration and scripts that installs, starts, stops,
and optionally backs up a piece of software on the cluster. Examples: ClickHouse, Presto, OpenSearch,
and the built-in `cqlite-flight` and `trino-loadtest` kits, which form an offline SSTable
read path (an Arrow Flight data plane and a read-load driver — documented for users in
[`docs/user-guide/cqlite-flight.md`](../user-guide/cqlite-flight.md)). Its `cqlite` Trino
catalog will be a catalog of the trino kit (`kits/trino/catalogs/cqlite.properties.template`),
not a standalone kit; it is not shipped until `pmcfadin/cqlite#2869` delivers the plugin.

Each kit lives under `src/main/resources/com/rustyrazorblade/easydblab/kits/<name>/`.

After `easy-db-lab kit install <name>` runs, the kit directory is copied to the cluster's working
directory. The `<name> start`, `<name> stop`, etc. subcommands then drive it.

## Directory Layout

Everything for a kit lives in `src/main/resources/com/rustyrazorblade/easydblab/kits/<name>/`:

- `kit.yaml` — required: the kit definition
- `bin/` — optional: shell scripts (`start.sh.template`, `stop.sh.template`, ...) for phases that
  have no typed steps. Every `bin/*.sh` becomes a command, so put only phase scripts here.
- `lib/`, `pod/` and other directories — optional: shared script code, scripts that run inside a
  pod, query files. They are copied into the kit directory and never become commands
  (`clickhouse-benchmark` keeps its sourced functions in `lib/` and its pod-side scripts in `pod/`).
- `dashboards/` — optional: Grafana dashboard JSON files
- `<name>.yaml.template` — optional: K8s manifest templates applied by `manifest` steps
- `METRICS.md` and `metrics-catalog.json` — recommended for any kit that exposes metrics

## kit.yaml Reference

If a kit's `kit.yaml` does not parse or fails validation, the kit has no commands: an installed kit gets no `<kit>` commands, and a kit in your profile or a kit source gets no `kit install <kit>`. Every command prints an error that names the kit, the file and the problem, and the other commands still run. `kit list` shows the kit as `invalid:` with the file and the cause.

```yaml
name: myworkload
type: db                 # db | app: the node pool the kit needs; install fails if it has no nodes
description: Short description shown in help text
version: "1.0.0"
collision-check: false   # true = refuse a second install and a start while running; or a map per phase (see Collision check)
requires: []             # node capabilities the kit's pods need, e.g. [host-path]; see Required node capabilities

metrics:                 # a list; see Metrics section
  - type: scrape         # scrape | java-agent | helm-native
    port: 9090

runtime:
  type: helm             # see Runtime section
  release: myworkload
  # namespace: optional; the cluster namespace when omitted

endpoints:
  - name: "HTTP UI"
    node-type: app       # "app" or "db"
    port: 8080
    type: http           # http | https | jdbc | native | cql | postgresql | mysql | kafka
    scheme: ""           # optional: used for JDBC URLs (jdbc type only)
    path: ""             # optional: appended to URL (http/https/jdbc only)
    database: ""         # optional: logical database name (postgresql and mysql types)

args:
  - flag: --workers
    variable: WORKERS
    description: "Number of workers"
    type: int            # string | int | float | boolean | kit-ref | extension
    capability: sql      # optional: for kit-ref, a capability the target must declare
    endpoints: [native]  # optional: for kit-ref, endpoint types the target must declare
    required: false
    default: "${APP_NODE_COUNT}"

hooks:
  post-workload-start:
    script: bin/update-catalogs.sh
    workloads: []       # optional: only fire when these kits start
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
| `start` | `easy-db-lab <name> start` | Metrics registered, dashboards installed, hooks fired. A failed metrics registration or dashboard install fails `start` |
| `stop` | `easy-db-lab <name> stop` | Waits for the runtime's pods to go, metrics deregistered, kit dropped from `runningKits`, hooks fired |
| `uninstall` | `easy-db-lab <name> uninstall` or `kit uninstall <name>` | Kit directory deleted from working dir. A kit still running has its `stop` phase run first, stop wait included; if that fails, the uninstall fails without running its steps. After the uninstall steps the kit is released as `stop` releases it (metrics deregistered, dropped from `runningKits`, hooks fired) |
| `backup` | `easy-db-lab <name> backup <name>` | `BACKUP_NAME` env var set to first argument |
| `restore` | `easy-db-lab <name> restore <name>` | `BACKUP_NAME` env var set to first argument |

If no typed steps are defined for a phase and a matching script exists in `bin/` (e.g.
`bin/start.sh`), the script is executed instead.

## Step Types

All phases use the same set of typed steps.

### Where helm and kubectl run

The `helm-repo`, `helm`, `helm-uninstall`, `namespace`, `manifest`, `manifest-url`, `kustomize`,
`wait` and `delete` steps (and the kit status check that asks helm whether a release exists) run
`helm` and `kubectl`. Where the tool runs depends on the cluster:

- **AWS**: on the control node over SSH, with `KUBECONFIG=/etc/rancher/k3s/k3s.yaml`. The tools are
  baked into the AMI; nothing is needed on your machine for these steps.
- **Local cluster (no SSH host)**: as a process on your machine, in the same environment `shell`
  steps get (the `<workspace>/bin` wrappers first on `PATH`, `KUBECONFIG` set to the workspace
  `kubeconfig`). **`helm` and `kubectl` must be installed on the host**, as `shell` steps already
  require. A missing tool fails the step with a message that names it. Nothing is skipped. The CLI
  picks the form from the workspace's recorded environment: the control node for an EC2 + K3s
  cluster (and outside a workspace), the local process for a K3s in Docker workspace or a
  Kubernetes cluster you bring. There is no switch to set. In a K3s in Docker workspace the
  wrappers find `EDL_NETWORK_MODE=direct` in the env file, so they run the tool with no tunnel and
  leave your own proxy variables as they are.

On a local run, helm keeps its state under `<workspace>/.helm/` (`config/`, `cache/` and `data/`),
not in your `~/.config/helm`, so a `helm-repo` step adds the repository to the workspace only.
`clean` does not remove `.helm/`; delete it yourself to forget the repositories and cached charts.
Manifests and `helm` values are piped to the tool, so a local run writes no temporary file.

A failed step reports the tool's exit status and the last lines of its stderr, in both forms.

### `helm-repo`
Adds a Helm chart repository. `name` and `url` accept `${VAR}` interpolation, like the other
helm steps.
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
  namespace: clickhouse-operator  # optional: the cluster namespace when omitted
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
  namespace: clickhouse-operator
```

When one operator serves several kit instances, `keep-while-any` names a resource type and keeps
the release while any object of that type is left in any namespace, reporting what still uses it
(`Kit.HelmReleaseKept`). Plain `postgres` and every `postgres-<extension>` share the CNPG operator:
```yaml
- type: helm-uninstall
  release: cnpg-operator
  namespace: cnpg-system
  keep-while-any: clusters.postgresql.cnpg.io
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
Creates a Kubernetes namespace carrying the cluster's ownership labels
(`app.kubernetes.io/managed-by=easy-db-lab`, `easydblab/cluster=<id>`), or adds those labels to
an existing one. Use it for an operator's own namespace, before the `helm` step that installs
into it; never install into `kube-system` or another namespace easy-db-lab does not own.
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
  namespace: my-operator     # default: the cluster namespace
  condition: Available   # default: Available
  timeout: 300s          # default: 300s
```

### `delete`
Deletes K8s resources, either one object by name or every object a label selector matches.
Both forms run `kubectl` where the other typed steps do (see [Where helm and kubectl
run](#where-helm-and-kubectl-run)). A step that mixes the two forms, or has neither, is rejected
when `kit.yaml` loads.

By name — `kind` and `name` are required:
```yaml
- type: delete
  kind: ClickHouseInstallation
  name: clickhouse
  namespace: lab-a           # default: the cluster namespace
  ignore-not-found: true     # default: true
```

By label — `selector` and `kinds` are required:
```yaml
- type: delete
  kinds: [deployment, replicaset, pod, service, configmap]
  selector: easydblab/kit=memcached
  # namespace: optional; the cluster namespace when omitted
```
Every object of the listed kinds in `namespace` that `selector` matches is deleted. When nothing
matches, the step succeeds and prints nothing. A failed cluster query fails the step. `selector`,
`kinds` and `namespace` accept `${VAR}` interpolation. Use this form in `stop` and `uninstall` for
kits that label every object they create, instead of a `shell` step.

### `kit-storage`
Readies the kit's storage for its claims, as the environment says (see
[Kit Storage](../user-guide/platform-substrate.md#kit-storage)).
```yaml
- type: kit-storage
  node-type: db                       # default: db
  count: 1                            # optional: defaults to node count
  volume-claim-template-name: data    # default: data; names the PVs and their directory
  storage-size: 10Ti                  # optional: PV capacity; defaults to the STORAGE_SIZE variable
  if-set: EXTSTORE_SIZE               # optional: skip the step unless this kit variable is non-empty
```
On local volumes (owned nodes) it creates the kit's class `edl-local-<kit>`, the directory
`/mnt/db1/<kit>/<claim template>` on each node, and one PV of that class per node, named
`<claim template>-<kit>-<ordinal>`. A kit with two claim templates on one pool (TiDB's TiKV and
TiFlash) declares one step per template. On a provisioned class it creates nothing and only checks
that the class still reclaims with `Retain`, failing before any claim exists if it does not.

`if-set` makes the volume optional: the memcached kit creates its extstore PV only when
`--extstore-size` is set. Pair the step with `kit-storage-delete` (`node-type`) in `uninstall`,
which removes the kit's PVs, its class and `/mnt/db1/<kit>` on that pool, is a no-op when no PV
was created, and does nothing on a provisioned class.

Every claim the kit creates — its own, a StatefulSet's, or one an operator generates — names
`__KIT_STORAGE_CLASS__` and requests `__STORAGE_SIZE__`, with no `selector` and no `volumeName`; a
selector or a PV name can never bind on a provisioned class, and a claim with no class lands on
the cluster's default class, which may delete. Declare the size arg as
`--storage-size` / `STORAGE_SIZE` with `default: "${KIT_STORAGE_SIZE}"`. An operator that can
delete claims on its own (Strimzi's `deleteClaim`, TiDB's `pvReclaimPolicy`) must keep them:
`deleteClaim: false`, `pvReclaimPolicy: Retain`. A non-root image whose entrypoint does not
chown its data directory needs `fsGroup` set to its uid, because a provisioned volume's root is
root-owned. `KitClaimStorageTest` checks every built-in kit for all of this.

### `configmap`
Creates or updates a K8s ConfigMap.
```yaml
- type: configmap
  name: my-config            # in the cluster namespace; `namespace:` names another
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
  pod: my-pod-name           # in the cluster namespace; `namespace:` names another
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

### How local scripts reach the cluster

Shell steps in `install:` and in phases, phase scripts in `bin/`, and hook scripts all run on your
machine with the same two settings:

- `KUBECONFIG` is the absolute path of the workspace `kubeconfig`. The process does not start if
  that file does not exist.
- `<workspace>/bin` is first on `PATH`. easy-db-lab writes a wrapper there for each of `kubectl`,
  `helm`, `cilium`, `curl`, `skopeo`, `k9s`, `ssh`, `scp`, `sftp` and `rsync` before the process
  starts. The `ssh`, `scp`, `sftp` and `rsync` wrappers use the workspace `sshConfig`, so a step can
  run `ssh db0 ...` by the node's alias; they never use the tunnel.

On a SOCKS cluster (no Tailscale), each of the other wrappers sends its call through the SOCKS5 tunnel that
easy-db-lab keeps open, and the tool reaches the private cluster addresses. On a Tailscale cluster
the wrappers run the tool unchanged, and it connects directly. The CLI starts or restarts the
tunnel before the first step runs, so a kit never has to.

The `kubectl`, `helm`, `cilium` and `k9s` wrappers always set `KUBECONFIG` to the workspace `kubeconfig` for their call, in place of any inherited value, on SOCKS and Tailscale clusters. A bare `<workspace>/bin/kubectl` therefore works with no setup, from a step or from your own shell. If the workspace has no `kubeconfig` file, the wrapper names the missing file and exits 1. The `curl` and `skopeo` wrappers leave `KUBECONFIG` unchanged.

Indirect calls go through the wrappers too, because they look the tool up on `PATH`: `timeout 60
kubectl ...`, `echo ns | xargs kubectl get`, `sh -c 'helm list -A'`, and a script in the kit's own
`bin/` that calls `kubectl`.

Some things are not wrapped:

- Tools other than those six, `aws` included, get no proxy settings and connect directly.
- A call that bypasses `PATH` is not wrapped: an absolute path such as `/usr/local/bin/kubectl`,
  `env -i kubectl`, or a script that sets its own `PATH=/usr/bin:/bin`. Call the tool by its name
  and keep the inherited `PATH`.
- A wrapped tool sends all of its traffic through the tunnel, public URLs included. `helm repo add`
  of a public chart repository works, but it is slower than a direct connection.

If a wrapper says that no SOCKS tunnel is recorded, run `easy-db-lab socks start` in the workspace.

## Environment Variables

All scripts and shell steps receive the following environment variables:

| Variable | Description |
|----------|-------------|
| `CLUSTER_NAME` | Name of the cluster |
| `KUBECONFIG` | Absolute path to the workspace kubeconfig file |
| `PATH` | `<workspace>/bin` (the tool wrappers) followed by the inherited `PATH` |
| `CONTROL_HOST` | Public IP of the control node |
| `CONTROL_HOST_PUBLIC` | Public IP of the control node |
| `CONTROL_HOST_PRIVATE` | Private IP of the control node |
| `DB_NODE_COUNT` | Number of database nodes |
| `APP_NODE_COUNT` | Number of app/stress nodes |
| `DB_NODE_IPS` | Comma-separated private IPs of database nodes |
| `APP_NODE_IPS` | Comma-separated private IPs of app nodes |
| `DB_NODE_LABELS`, `APP_NODE_LABELS`, `CONTROL_NODE_LABELS` | The role's node label selector (`type=db` on the nodes easy-db-lab builds, the `init --role-nodes` mapping, or empty when the role runs on any node). Pass it to `kubectl get nodes -l` only when it is not empty |
| `DB_NODE_SELECTOR`, `APP_NODE_SELECTOR` | The same selector as a JSON map for a pod's `nodeSelector` (`{"type":"db"}`, or `{}` when unconstrained). Templates take `__DB_NODE_SELECTOR__` / `__APP_NODE_SELECTOR__` |
| `DB_AFFINITY` | A CNPG `Cluster` `affinity` for the db role: `{"nodeSelector":{...}}`, or `{}` when unconstrained. CNPG restarts an instance on every reconcile when the Cluster names an empty `nodeSelector`, so the postgres kit uses `affinity: __DB_AFFINITY__` instead of `__DB_NODE_SELECTOR__` |
| `BUCKET_NAME` | S3 data bucket name |
| `ACCOUNT_BUCKET` | S3 account-level bucket name |
| `REGION` | AWS region |
| `KIT_NAME` | Name of this kit |
| `NAMESPACE` | The cluster namespace: the one `init --namespace` recorded, else `default` on a cluster easy-db-lab builds and `easy-db-lab` on one you bring with `--kubeconfig`. Reserved: a kit arg may not take this name. Shell steps and `bin/` scripts pass it to every namespaced `kubectl` and `helm` call (`-n "${NAMESPACE}"`); templates take `__NAMESPACE__`, and in-cluster DNS names are `<svc>.${NAMESPACE}.svc`. A typed step or `runtime` block with no `namespace` uses it |
| `STORAGE_SIZE` | Claim size (from the `--storage-size` arg, if used) |
| `KIT_STORAGE_CLASS` | The StorageClass every kit claim names: `edl-local-<kit>` on local volumes, else the `init --kit-storage-class` class |
| `KIT_STORAGE_SIZE` | The environment's default claim size: `init --kit-storage-size`, else `10Ti` on local volumes or `100Gi` |
| `RUNNING_KITS` | Comma-separated names of currently running kits |
| `EASY_DB_LAB_EXEC` | Path to the `easy-db-lab` binary |
| `BACKUP_NAME` | First positional argument (backup and restore phases only) |
| `EDL_KIT_LIB` | Absolute path of the shared kit shell library, `<workspace>/bin/edl-kit-lib.sh` (see [Shared shell library](#shared-shell-library)) |
| `EDL_NODE_SSH` | `true` when the cluster has the `node-ssh` capability (it reaches its nodes over SSH), else `false`. It comes from the capability set `init` recorded, not from whether the workspace has an `sshConfig`, so `init --capability node-ssh=false` gives `false`. Gate any node check over SSH on it; never read a failed `ssh` as "no SSH" |

Args declared in `kit.yaml` under `args:` are also injected using their `variable` name. For
example, `--workers` with `variable: WORKERS` becomes `$WORKERS`. An arg the user leaves out that
has no default is set to an empty string, never the string `null`, and it replaces a variable of
the same name that the operator's shell exports; test it with `[ -n "${WORKERS}" ]`. A `boolean`
arg takes an optional value: a bare `--flag` means `true`, and `--flag false` or `--flag=false`
turns off a flag whose default is `true` (`--help` shows `--flag[=<value>]`).

### Command args

Install args (`args:`) are given once, at `kit install`, and stored in `resolved-args.env`. A kit
can also declare options for one command, including a lifecycle phase such as `start`, under
`commands:`. A command arg applies to that run only, so it can change between runs without a
reinstall, and it wins over an install arg with the same variable.

```yaml
commands:
  start:
    description: "Start the workload"
    args:
      - flag: --log-level
        variable: LOG_LEVEL
        type: string
        default: "info"
      - flag: --heap-profile
        variable: HEAP_PROFILE
        type: boolean
      - flag: --env
        variable: EXTRA_ENV
        type: string
        repeatable: true
```

Install args and command args are parsed the same way:

- An optional arg that is not given and has no default is set to an empty string, never the
  string `null`, and it replaces a variable of the same name that the operator's shell exports.
- A boolean arg is `false` when its flag is not given and `true` when it is given bare.
- `--help` shows an arg's declared default after its description, as `(default: <value>)`. A
  boolean flag's implicit `false` is not shown.
- A string command arg can set `repeatable: true`. The flag can then be given any number of
  times, and the variable holds every value in order, one per line. Read it with
  `while IFS= read -r line; do ...; done <<< "${EXTRA_ENV}"`. A repeatable arg that is not given
  is an empty string. `repeatable` is refused on a non-string arg, and on an install arg, because
  `resolved-args.env` stores one `KEY=VALUE` per line; the kit then fails to load with an error
  that names the arg.

`kit info <kit>` lists the install args and then each command's args under the command name,
with the flag, variable, description, default, and a `[repeatable]` marker.

### Addressing per-node services (e.g. the Cassandra Sidecar)

Some cluster services run as a **`hostNetwork` DaemonSet — one instance per db node**, addressable
at `<db-node-private-ip>:<port>` with no cluster-wide load-balanced Service (the Cassandra Sidecar
on port `9043` is the canonical example). Each instance is **node-local**: a request only affects the
node it fronts. A kit that needs to reach *every* such instance (for example, creating a per-node
snapshot before a distributed read) must fan the call out across all nodes rather than wiring a
single node's URI.

Use `DB_NODE_IPS` — the comma-separated list of all db-node private IPs — as the enumeration source:

```bash
# Create a Sidecar snapshot on every db node, not just one
IFS=',' read -ra DB_IPS <<< "$DB_NODE_IPS"
for ip in "${DB_IPS[@]}"; do
  curl -sf -XPUT "http://${ip}:9043/api/v1/keyspaces/${KS}/tables/${TBL}/snapshots/${SNAP}"
done
```

Wiring only one node's address (e.g. `db0`) leaves the other nodes without the resource, so any work
that lands on `db1`/`db2` fails. `DB_NODE_IPS` is part of the stable variable contract above, so this
pattern does not depend on topology discovery from within the kit.

Default values in `kit.yaml` can reference any of the variables above using `${VAR}` syntax:
```yaml
default: "${APP_NODE_COUNT}"
```

A single arg cannot produce two *derived* forms — there is no transform syntax. If a kit needs
the same value in different shapes (e.g. Flink uses the image tag `1.20` and the `flinkVersion`
enum `v1_20`), hardcode the derived form in the manifest template and document the lockstep
coupling with the arg.

## Metrics

The `metrics` field is a list of entries, each with a `type`, telling easy-db-lab how the kit's
metrics reach the collector. When `start` succeeds, `scrape` entries are registered. When `stop`
succeeds, they are deregistered.

### `scrape` — Prometheus endpoint
The kit exposes a Prometheus endpoint and the OTel collector DaemonSet scrapes it.
```yaml
metrics:
  - type: scrape
    port: 9090                    # required
    path: /metrics                # optional, default: /metrics
    job: myworkload               # optional, default: the kit name; unique within the kit
    pod-selector: "app=myworkload"  # optional: scrape the matching pods directly (see below)
    username: ""                  # optional: basic-auth user
```

Without a `pod-selector`, every collector scrapes `localhost:<port>`, so the port must be reachable
on the node: a NodePort, or a `hostPort` on the pod. With a `pod-selector`, the collector finds the
matching pods through Kubernetes pod discovery and each node's collector scrapes only the pods on
its own node, on the pod IP and `port` (the container port). Every pod gets its own `instance`
label, and no metrics NodePort is needed. See `kits/memcached/kit.yaml`.

**Use a `pod-selector`.** A job without one runs on every collector in the DaemonSet, whatever
node the workload is on: a NodePort answers on every node, so a single-instance kit reports one
duplicate series per node, each with a different collector's hostname as `instance`, and a hostPort
answers only on its own node, so every other node reports the job down. Every built-in kit scrapes
by pod discovery, and `NodePortKitScrapeTest` fails if one declares a static scrape. A kit that
runs several instances side by side (postgres and `postgres-<extension>`) names its own with
`${KIT_NAME}` in the selector, which is filled in with the instance name at `start`.

**Before adding any reporter plumbing, check whether the workload's image already ships the
metrics reporter** — a pre-staged plugin directory, a built-in endpoint, or a bundled jar
already on the classpath. Many JVM images do (e.g. the official Flink image pre-stages the
Prometheus reporter at `/opt/flink/plugins/metrics-prometheus/`; you only set the reporter
config — no plumbing). Only add an initContainer or volume to stage a reporter jar if it is
genuinely absent.

**Never mount an `emptyDir` over a directory the image already populates** — it hides what the
image staged there. (We hit a crash copying the Flink reporter jar from an assumed path that
did not exist, while the `emptyDir` overlay masked the real pre-staged plugin dir.)

If writing the ConfigMaps fails, `start` emits `Kit.MetricsRegistrationFailed`, naming the kit
and the failure, and exits non-zero. The kit's pods still run.

Registration creates one K8s ConfigMap per scrape entry, named `easydblab-metrics-<kit>-<job>` and
labelled `easydblab.com/workload-metrics=true`. `OtelSyncService` reads these ConfigMaps and
regenerates the OTel collector config with a scrape job for each. Scraped metrics receive the
entry's `job` and `cluster=<cluster-name>` labels automatically.

A `hostPort` scrape assumes **one metrics-exposing pod per node per kit**. A kit that exposes
metrics from multiple pods (e.g. Flink serves `:9249` on the JobManager *and* every TaskManager)
will collide on the hostPort if two land on the same node. Prefer a `pod-selector`; otherwise
spread them with `podAntiAffinity` and keep replicas below the node count.

### `java-agent` — OpenTelemetry Java Agent
For JVM kits. The OTel Java agent is attached to the JVM process.
```yaml
metrics:
  - type: java-agent
    service-name: myworkload
```

The agent pushes OTLP to the core collector's Service; nothing in the collector config changes.
Set `OTEL_SERVICE_NAME` (it becomes `job`) and `OTEL_EXPORTER_OTLP_ENDPOINT=__OTEL_OTLP_HTTP_ENDPOINT__`.

### Delivering an agent: an init container

Never mount an agent from a host path: `/usr/local/otel` and `/usr/local/pyroscope` exist only on
the AMI, so a kit that mounts them runs nowhere else (`BuiltinKitHostDependencyTest` fails on one).
Copy the agent in with an init container instead: it runs the agent's pinned, multi-arch image
(`__OTEL_AGENT_IMAGE__`, whose `/javaagent.jar` is the OTel agent, or `__PYROSCOPE_AGENT_IMAGE__`,
whose `/pyroscope.jar` is the Pyroscope agent; Kotlin names them in `JavaAgent`), copies the jar into
an `emptyDir` as a non-root user, and the main container mounts that volume read-only and loads the
jar through its JVM options. Kotlin-built pods (stress Jobs) use `JavaAgentDelivery`, which adds the
volume, one init container per agent and the mount. See `kits/cassandra-pods/statefulset.yaml.template`
and `kits/neo4j/statefulset.yaml.template`.

Both images must be in their registry before a pod can load the agent.
`JavaAgentImagesPublishedIntegrationTest` asks the registry for each `JavaAgent` image and fails
for any image it does not have. The Pyroscope image is built from `docker/pyroscope-java-agent/` by
the `publish-pyroscope-agent` job of `publish-container.yml`. That job runs on a tag push, after PR
Checks pass on main, or by manual dispatch, so a branch that changes the agent version can publish
the image before it merges. When you bump `Constants.PyroscopeJavaAgent.VERSION`, dispatch the
workflow on that branch.

An agent upgrade changes `PYROSCOPE_VERSION` and `PYROSCOPE_SHA256` in the Dockerfile together,
and `Constants.PyroscopeJavaAgent.VERSION` to match. The build checks the downloaded jar against
the SHA-256, so a version without its new checksum fails. A published tag never changes: the job
skips a version that is already in the registry. To fix a published image, bump the version
instead of pushing to its tag again.

**Reserved NodePorts:** 30942 is the `cassandra-pods` CQL port. Pick a port no other kit uses
(`BuiltinKitNodePortTest` fails on a duplicate).

**Never write a double underscore in a `.template` file.** `__NAME__` is the install-time
placeholder syntax, so a literal `__` (e.g. Neo4j's `NEO4J_server_bolt_advertised__address`)
swallows the text up to the next `__`. Put such a name in `kit.yaml` instead — the Neo4j kit's
start step writes it as a ConfigMap key and the pod loads it with `envFrom`.

### `helm-native` — Built-in telemetry
The kit ships its own metrics pipeline via Helm values. No OTel config change is needed.
```yaml
metrics:
  - type: helm-native
```

### Documenting Metrics

Every kit that exposes metrics should include a `METRICS.md` file listing the available
metrics, their labels, and usage notes. This is the reference for anyone building dashboards.
See `kits/presto/METRICS.md` for an example.

### Exporting the Metrics Catalog

`metrics-catalog.json` lists every metric a running kit sends to Mimir under
`job="<kit>"`. It is exported from a live cluster by `bin/export-workload-metrics`, and the
committed copy is what `METRICS.md` and the kit's dashboards are built from.

The catalog is compact: `series` holds one entry per distinct metric name, and each entry's
`labels` maps a label key to the sorted distinct values seen for it, at most 20 per key
(`MAX_LABEL_VALUES` in the script). Per-pod identity labels (`k8s_pod_uid`, `k8s_pod_name`,
`instance`, `service_instance_id`) are dropped, so the file does not grow with the number of
pods.

Label keys present on every series of the export (the collector's infrastructure labels such as
`cluster`, `host_name`, `job` and `k8s_*`) are listed once, in the top-level `common_labels` map,
in the same key → values form and with the same cap. They are left out of every entry, so an
entry's `labels` holds only the labels specific to that metric, and is `{}` when it has none:

```json
{
  "workload": "clickhouse",
  "exported_at": "2026-09-23T12:00:00Z",
  "common_labels": {
    "host_name": ["db0", "db1", "db2"],
    "job": ["clickhouse"]
  },
  "series": [
    {
      "name": "ClickHouseMetrics_Query",
      "labels": { "service_name": ["clickhouse"] }
    },
    {
      "name": "target_info",
      "labels": {}
    }
  ]
}
```

A series' full label set is its entry's `labels` plus `common_labels`.

The transform lives in `bin/metrics-catalog.jq`, so a full per-series export (the older format,
one entry per series with a single value per label) can be converted offline with the same code
the script uses:

```bash
jq -L bin --argjson cap 20 \
   'include "metrics-catalog"; del(.series) + (.series | compact_catalog($cap))' \
   old-catalog.json > metrics-catalog.json
```

Only series with a sample in the last five minutes are exported, so series left behind by pods
that have since been replaced do not end up in the catalog. Export while the kit is running and
being scraped.

The script is a kit-development tool and is **not packaged**: the Homebrew and release
distributions carry only `packer/` and the agents, so it needs a source checkout. It sources
`env.sh`, so run it **from the cluster workspace** (the directory holding `env.sh`, `state.json`
and `kubeconfig`), calling it by its path in your checkout:

```bash
cd <cluster-workspace>
<checkout>/bin/export-workload-metrics <kit>
```

It queries Mimir on its NodePort, `http://<control private IP>:31009/prometheus`, which is
reachable from inside the VPC. From the workspace it queries through the workspace's `bin/curl`
wrapper, which routes through the SOCKS tunnel (`easy-db-lab socks start`); without that wrapper it
uses plain `curl`. Where the address is unreachable, or the backends are ClusterIP only, it fails
naming that address; port-forward Mimir and pass its URL in `MIMIR_URL` instead:

```bash
kubectl port-forward svc/mimir 19009:9009 &
MIMIR_URL=http://127.0.0.1:19009/prometheus <checkout>/bin/export-workload-metrics <kit>
```

It writes `<cluster-workspace>/<kit>/metrics-catalog.json`, not the kit's resource directory.
That `<kit>/` directory is the kit's working copy, and `<kit> uninstall` deletes it, so copy the
file into the source tree before uninstalling:

```bash
cp <cluster-workspace>/<kit>/metrics-catalog.json \
   <checkout>/src/main/resources/com/rustyrazorblade/easydblab/kits/<kit>/metrics-catalog.json
```

## Hooks

Hooks let one kit react when another kit starts or stops. The hook script runs in the
context of the *declaring* kit, not the triggering one.

```yaml
hooks:
  post-workload-start:
    script: bin/update-catalogs.sh
    workloads: [cassandra]   # optional: only fire when cassandra starts
  post-workload-stop:
    script: bin/update-catalogs.sh
```

When `easy-db-lab cassandra start` completes, easy-db-lab scans every installed kit
directory, finds those with a matching `post-workload-start` hook, and fires them.

If `workloads` is empty or omitted, the hook fires for any kit start/stop. Hooks retry up
to 3 times with exponential backoff (1s, 2s, 4s) on failure.

**Use case**: Presto registers its Cassandra catalog after Cassandra starts. Its
`post-workload-start` hook runs `bin/update-catalogs.sh` which re-registers catalogs for all
currently running data sources.

## Grafana Dashboards

After a successful `start`, easy-db-lab installs dashboards into Grafana via the HTTP API.

**Auto-discovery** (default): any `.json` files in `dashboards/` are installed automatically.
Files are installed in alphabetical order into a Grafana folder named after the kit.

If a dashboard does not reach Grafana, `start` exits non-zero, for every kit. A declared
dashboard file that is missing, or one that Grafana rejects, emits
`Grafana.KitDashboardInstallFailed`, naming the kit, the dashboard and the reason. Dashboards or
a tenant listing that cannot be read or rendered emit `Grafana.KitDashboardsSkipped`, naming the
kit, its dashboards and the reason. The kit's pods still run.

**Explicit list** (optional): declare dashboard paths in `kit.yaml` to control selection or
order:
```yaml
dashboards:
  - path: dashboards/overview.json
  - path: dashboards/queries.json
    name: Query Details
```

A kit with an `extension` arg (postgres) runs as several instances side by side (`postgres`,
`postgres-duckdb`). A dashboard with `extension:` is installed only by the instance created with
that extension; one without is installed by every instance. Grafana uids are global, so an
instance other than the kit's own installs each dashboard under the uid suffixed with its
extension (`postgres-overview-duckdb`), and links between the dashboards it installs point at
its own copies; it never moves another instance's dashboard into its folder. A link to another
extension's dashboard points at the uid that extension's instance installs it under
(`/d/postgres-duckdb` becomes `/d/postgres-duckdb-duckdb`), in every instance.
```yaml
dashboards:
  - path: dashboards/postgres.json
  - path: dashboards/duckdb.json
    extension: duckdb
```

Dashboard JSON files should:
- Use `"uid": "<kit>-kit"` to make re-installs idempotent
- Declare the `cluster` variable: `label_values(up, cluster)` on `${metrics_datasource}`, multi-select, with "All" and `allValue` `.+`. Leave its `current` empty.
- Filter every metrics and logs selector by `cluster=~"$cluster"`, including the queries of other variables such as an instance list: `label_values(cnpg_collector_up{cluster=~"$cluster"}, instance)`. Clusters of one tenant share the store, so a selector without the filter mixes every cluster that ran at the same time. In a binary expression, each side needs its own filter.
- Keep `cluster` in every aggregation (`sum by (cluster, instance)`, `sum by (cluster) (...)`), and show it in every legend by its short name: wrap the query in `label_replace(<expr>, "cluster_name", "$1", "cluster", "(.+-[0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")` and write the legend as `{{cluster_name}} {{instance}}`. The short name keeps the first 8 characters of the id (`test-1a2b3c4d`), so two clusters that share a name still differ. Two selected clusters then draw as separate series, each named. A field override matches the prefixed legend (`byRegexp` `^(.* )?reads$`, not `byName` `reads`). A table keeps its legends and shows the cluster as a column: its `organize` hides `cluster` and renames `cluster_name` to "Cluster".
- Declare a datasource picker for each signal they use: `metrics_datasource` (`"type": "datasource", "query": "prometheus"`, label "Metrics"), and `logs_datasource` (`loki`) or `traces_datasource` (`tempo`) when they use logs or traces. Leave each picker's `current` empty.
- Name every datasource through its picker, keeping the `type`: `{ "type": "prometheus", "uid": "${metrics_datasource}" }`. Never name `mimir`, `loki` or `tempo` directly, not even inside an Explore link; `pyroscope` has no picker and stays as it is.
- Pass each picker and `cluster` the dashboard declares on every `/d/` link, as `${metrics_datasource:queryparam}` (or an explicit `var-<name>=`), and no `${name:queryparam}` for a variable it does not declare.
- Include `"tags": ["<kit>", "kit"]`

`DashboardDatasourceVariablesTest` checks the picker and link rules on every kit dashboard, `ClusterFilterTest` checks the `cluster` variable and filter, and `SeriesClusterTest` checks the aggregations and legends. `dashboards/CLAUDE.md` in the repository has the details.

On `start`, the dashboards go through the same install-time pass as the core dashboards (`DashboardDefaults`): each picker selects the cluster's own tenant and `cluster` defaults to the current cluster, so a dashboard opens on the cluster with no selection by hand. Dashboards are installed with `overwrite: true`, so re-running `start` never duplicates them.

## Collision check

`collision-check` is a boolean or a map of phase to boolean. `true` guards both `install` and
`start`; `false` (the default) guards neither. The map guards only the phases set to `true`:

```yaml
collision-check:
  start: true     # refuse start while running
  install: false  # a second install overwrites the scaffold without --force
```

`install` and `start` are the only phases with a collision check; any other key is rejected when
the kit is loaded. Each guarded phase fails with an error event and exits non-zero:

- **`kit install`** refuses when the kit's scaffold directory already exists and is not empty. The
  event is `Install.CollisionDetected`. Pass `--force` to overwrite the scaffold.
- **`<kit> start`** refuses when the workload the kit's `runtime` block declares is already in the
  cluster: pods matching the runtime `selector` in its namespace, or, for a `helm` runtime, the
  helm release and then any pod labelled `app.kubernetes.io/instance=<release>` in the runtime's
  namespace. Pods that are still terminating count too, so a pod left cleaning up by a manual
  `kubectl delete` blocks `start` until it is gone. The event is `Kit.CollisionDetected`, naming
  the objects it found, and no start step runs. Run `<kit> stop` first.

`<kit> stop` is not a collision check, and it applies to every kit that declares a `runtime`,
guarded or not. Once its steps succeed, it waits until none of the runtime's pods is left,
terminating pods included. For a `helm` runtime that means the pods labelled
`app.kubernetes.io/instance=<release>`; the release itself does not count, because a kit may stop
by scaling its release to zero (Presto, Trino) and keep it. Deleting a StatefulSet, Deployment or
operator resource, or scaling a Deployment to zero, returns before its pods have terminated, so
this wait is what makes `stop` return only once the workload is gone, and `stop` followed by
`start` work. It gives up after 5 minutes with a `Kit.StopIncomplete` error event naming what is
left. A failed cluster query during the wait is retried; if the last look still fails, `stop`
exits non-zero with a `Kit.StopUnverified` error event carrying the cause. A kit with no `runtime`
block does not wait.

`<kit> uninstall` waits the same way after its steps succeed — a `helm uninstall` (Presto, Trino)
also returns while the release's pods are still terminating — and reports a pod that outlives the
wait with the same events, naming the `uninstall` phase; the kit directory is then kept.

The runtime must therefore name what `start` creates and `stop` removes. A runtime pointing at
something the `install` phase creates, such as an operator's helm release, would refuse every
`start` after a successful install. A kit with no `runtime` block is looked up by the pod label
`app.kubernetes.io/name=<kit>`.

## Pod network tuning

Every pod in the pod network gets the tuned network sysctls the nodes run, with no work in the
kit. `up` applies one cluster-wide MutatingAdmissionPolicy, `easydblab-pod-network-sysctls`,
before the observability stack and before any kit. It adds the per-namespace keys of the node tuning file
(`somaxconn`, `tcp_max_syn_backlog`, the TCP buffer and keepalive keys,
`tcp_slow_start_after_idle`, `tcp_tw_reuse`) to every new pod's `securityContext.sysctls`. See
[Node tuning](../user-guide/node-tuning.md) for the values.

- Do not add a `sysctls` block for these keys to a kit's manifests or chart values.
- A kit pod that sets one of these keys itself keeps its own value; the policy adds only the
  keys the pod lacks.
- A kit still never uses `hostNetwork`. A host-network pod is not changed by the policy.
- Pods keep the kernel default `ip_local_port_range` and no reserved ports.

To check a kit pod:

```bash
kubectl get pod <pod> -o jsonpath='{.spec.securityContext.sysctls}'
kubectl exec <pod> -- cat /proc/sys/net/core/somaxconn
```

## Runtime

The `runtime` field tells easy-db-lab how to find running pods for status checks, log tailing, and
the `start` collision check.

```yaml
runtime:
  type: helm          # helm | deployment | statefulset | pods
  release: presto     # for helm: the Helm release name
  namespace: presto  # optional: the cluster namespace when omitted
  selector: "app=presto"  # for pods: label selector
  name: presto            # for deployment/statefulset: resource name
```

## Profiling

Every kit running on the cluster is automatically profiled at the system level by the Grafana
Alloy eBPF DaemonSet — no per-kit setup required. This covers all processes including
non-JVM ones like ClickHouse.

**JVM kits get deeper profiling** via the Pyroscope Java agent, copied into the pod from
`PYROSCOPE_AGENT_IMAGE` by an init container (see [Delivering an agent](#delivering-an-agent-an-init-container)).
This enables method-level CPU, allocation, and lock contention profiles — much richer than eBPF.

### Wiring up the Java agent for a K8s kit

Call `edl_patch_pyroscope` from the [shared shell library](#shared-shell-library) in the `start`
phase, once per JVM deployment, before the rollout wait. It adds an init container that copies
`/pyroscope.jar` from `${PYROSCOPE_AGENT_IMAGE}` into an `emptyDir`, mounts that volume read-only
at `/edl/agents` in the JVM container, and sets `JAVA_TOOL_OPTIONS` from `edl_pyroscope_opts`,
which also makes the agent retry uploads without a limit and hold an hour of snapshots, so a
Pyroscope restart loses no profile. The agent never comes from a host path, so the pods run on any
node, and the patch declares no `hostPort`: clients reach a coordinator through the kit's NodePort
Service.
The agent ships to `PYROSCOPE_URL`, the Pyroscope Service by its cluster DNS name or, on a redirect
cluster, the external stack; never point it at `CONTROL_HOST_PRIVATE`, as no backend listens on a node
address (`BuiltinKitCollectorEndpointTest` fails on one).

```bash
. "${EDL_KIT_LIB:?run this through easy-db-lab, which sets EDL_KIT_LIB}"
CLUSTER_LABEL="$(edl_cluster_label)"

# <deployment> <container> <app> <cluster-label> <component>
edl_patch_pyroscope <deployment-name> <container-name> <kit> "${CLUSTER_LABEL}" coordinator
```

The `presto` and `trino` kits' `start.sh` are the reference. If a container builds its JVM
command line itself, put `$(edl_pyroscope_opts <kit>)` in it and give it the same init container
and `/edl/agents` mount, instead of writing the agent options by hand.

Pass `coordinator`, `worker`, and so on as the `<component>` argument to tell apart the JVM
processes of one kit; the helper adds it to the profile labels. Profiles appear in Grafana's Pyroscope
datasource under `service_name=<kit>`.

### Operator-managed (CRD) kits

For kits whose pods are managed by an operator (the Flink operator, CNPG, etc.), a `kubectl
patch` does **not** work — the operator reconciles its pods and reverts the patch. Put the JVM
options in the operator's own resource instead (e.g. a `FlinkDeployment`'s `flinkConfiguration`
or `podTemplate`, or the CR's pod template). For these kits the per-kit Java agent is optional
anyway — system-level eBPF profiling already covers them, so it can be deferred.

See `docs/user-guide/profiling.md` for how to access profiles in Grafana, profile types, and
the full observability data flow.

## Shared shell library

A kit script that runs pods sources the shared kit shell library instead of writing its own pod
placement, waits and gauge push. The CLI packages it as `configuration/edl-kit-lib.sh`, writes it to
`<workspace>/bin/edl-kit-lib.sh`, and sets `EDL_KIT_LIB` to its absolute path for every kit process
(phase scripts, `shell` steps and hooks). Source it at the top of the script:

```bash
#!/usr/bin/env bash
set -euo pipefail
. "${EDL_KIT_LIB:?run this through easy-db-lab, which sets EDL_KIT_LIB}"
```

Sourcing it changes no `set` option, trap or working directory, and defines only `edl_` functions
and `EDL_` variables. The functions read `KIT_NAME`, `NAMESPACE`, `KUBECONFIG`, `APP_NODE_LABELS`, `APP_NODE_SELECTOR` and `CONTROL_NODE_LABELS` from
the environment. A function that fails prints why to stderr and exits the script. Every function
that calls the cluster acts in `NAMESPACE` and exits with `NAMESPACE is not set` when it is unset or
empty, before any call.

| Function | What it does |
|---|---|
| `edl_fail <message>` | Prints the message to stderr and exits 1 |
| `edl_labels <role>` | Prints `easydblab/kit=${KIT_NAME},easydblab/role=<role>` |
| `edl_resource_name <role>` | Prints `${KIT_NAME}-<role>-$(date +%s)`, a unique pod or ConfigMap name |
| `edl_require_app_node <kit>` | Fails with `<kit> needs an app node (<APP_NODE_LABELS>); this cluster has none` when no node matches `APP_NODE_LABELS`, or, when it is empty (an app role that runs anywhere), when the cluster has no node |
| `edl_pod_overrides [<configmap> <mount-path> <container>]` | Prints the JSON for `kubectl run --overrides=<json> --override-type=strategic`: `nodeSelector` set to `APP_NODE_SELECTOR` (`{}` when unconstrained), and a mount of the ConfigMap when one is given. `kubectl run` names the container after the pod |
| `edl_create_configmap [--flag <flag>] <name> <role> <key>=<path>...` | Creates a labelled ConfigMap from files in one call; fails on a missing or empty file, or more than 1,000,000 bytes in all. With `--flag`, the over-limit error names that kit flag as the file to shrink |
| `edl_active_pods <role>` | Prints this instance's pods of that role that have not ended |
| `edl_wait_for_pod_running <pod>` | Returns once the pod leaves Pending (Running, Succeeded or Failed); fails at once on an image pull error with the reason and the pod's events, and after 10 minutes otherwise, and deletes the pod first so it does not block the next run |
| `edl_follow_pod_logs <pod> <handler>` | Streams the pod's log, calling the handler function with each line, and resumes a cut stream while the pod runs |
| `edl_wait_for_pod_exit <pod>` | Waits, with no limit, for the pod to end and prints its exit code |
| `edl_push_gauges <service> <name> <value>...` | Pushes OTLP gauges labelled `kit=${KIT_NAME}` to the collector; a non-numeric value is sent as 0; a failed push never fails the script and prints one warning per script |
| `edl_collect_catalogs <product> <catalogs-dir> <workload-root> [<infra-db>...]` | Collects the catalogs for a coordinator-and-workers helm kit into `EDL_CATALOG_CONTENT`: each infrastructure database whose properties file names its endpoint, each running kit that has a catalog file, and each custom `--from` workload with a `<product>-catalog.properties` |
| `edl_helm_upgrade_with_catalogs <release> <chart> <version> <values-file> <catalog-key>` | `helm upgrade --install` at the pinned version, with the collected catalogs under the chart value `<catalog-key>`; it does not wait for the rollout |
| `edl_cluster_label` | Prints the cluster label (`<name>-<id>`) from the `cluster-config` ConfigMap; fails when it is empty |
| `edl_patch_pyroscope <deployment> <container> <app> <cluster-label> <component>` | Patches a deployment to load the Pyroscope agent with the cluster and component profile labels. It declares no port: a coordinator is published on its kit's NodePort Service. The agent comes from an init container that copies it from `PYROSCOPE_AGENT_IMAGE` into an `emptyDir` mounted read-only at `/edl/agents`, never a host path |
| `edl_scale_to_zero <deployment>...` and `edl_helm_uninstall <release>` | The `stop` and `uninstall` steps of a helm kit |

Every wait fails with `pod <name> was deleted` when the pod is gone, for example after a `stop` in
another terminal. A typical run:

```bash
edl_require_app_node my-bench
POD="$(edl_resource_name bench-run)"
kubectl run "${POD}" --image="${IMAGE}" --restart=Never \
  --labels="$(edl_labels bench-run)" \
  --overrides="$(edl_pod_overrides)" --override-type=strategic \
  --command -- my-bench --threads "${THREADS}"
edl_wait_for_pod_running "${POD}"
edl_follow_pod_logs "${POD}" handle_line
EXIT_CODE="$(edl_wait_for_pod_exit "${POD}")"
[ "${EXIT_CODE}" = 0 ] || edl_fail "The run failed with exit code ${EXIT_CODE}"
```

Kit scripts run on the operator's machine, where `#!/usr/bin/env bash` can be the macOS bash 3.2.
The library, and every script that sources it, follows these rules:

- No associative arrays, `mapfile`, `${v,,}` or `wait -n`.
- Never expand an array that can be empty as `"${arr[@]}"` under `set -u`: bash before 4.4 fails
  with `unbound variable`. Add to an array only when there is at least one element to pass, or
  build the list another way.
- Assign a command substitution on its own line (`X="$(f)"`), never with `local X="$(f)"` or inside
  an `if` test, so a failure inside it still ends the script under `set -e`.

The library tests (`./gradlew testKitShellLib`) and the kit script tests also run under `/bin/bash`
when it is bash 3.x.

The library is written once into each workspace and never rewritten, so a workspace keeps the
library it was first given. When you edit the library, use a fresh workspace, or delete
`<workspace>/bin/edl-kit-lib.sh`, before you try the change; the next kit process writes the new
copy. Report parsers, argument checks and SQL belong in the kit's own `lib/`, not in the library.

## Bench Kits — cross-kit targeting

A bench kit benchmarks a running database kit. It declares a `kit-ref` arg that the user
populates with `--target <kit-name>` at install time. The framework then reads the target
kit's declared endpoints and injects them as `TARGET_*` environment variables into every phase
script.

### Declaring a kit-ref arg

```yaml
args:
  - flag: --target
    variable: TARGET
    type: kit-ref
    capability: sql          # optional: a capability the target must declare
    endpoints: [native, http] # optional: endpoint types the target must declare
    description: "Name of the running database kit to benchmark"
    required: true
```

`type: kit-ref` tells easy-db-lab three things:
1. The installed kit directory is named `<kit>-<target>` instead of `<kit>`, allowing
   multiple simultaneous instances (e.g. `sysbench-clickhouse` and `sysbench-tidb`).
2. At install, the target must already be installed and must provide what the arg asks for.
   `kit install` refuses, before it writes anything, a target that lacks the `capability` or any
   type in `endpoints`, and names every missing type in one message (for example
   `Target kit 'tidb' does not have the 'native' endpoint required by clickhouse-benchmark`). An
   unknown type in `endpoints` fails when `kit.yaml` is parsed. With neither field, the target is
   not checked.
3. In every phase, `KitEndpointResolver` reads the target kit's `kit.yaml` endpoints and
   injects them as `TARGET_*` environment variables.

### TARGET_* injection rules

The variables injected depend on what endpoints the target kit declares:

| Endpoint type | Variables injected |
|---------------|-------------------|
| `jdbc` | `TARGET_JDBC_URL`, `TARGET_JDBC_USER`, `TARGET_JDBC_DRIVER` |
| `postgresql` | `TARGET_PG_HOST`, `TARGET_PG_PORT`, `TARGET_PG_USER`, `TARGET_PG_DATABASE` |
| `mysql` | `TARGET_MYSQL_HOST`, `TARGET_MYSQL_PORT`, `TARGET_MYSQL_USER`, `TARGET_MYSQL_DATABASE` |
| `native` | `TARGET_NATIVE_HOST`, `TARGET_NATIVE_PORT`, `TARGET_NATIVE_USER`, `TARGET_NATIVE_DATABASE` |
| `http` | `TARGET_HTTP_URL` |
| `kafka` | `TARGET_KAFKA_BOOTSTRAP` |

`https` and `cql` endpoints inject nothing. Every `*_USER` variable comes from the target's `sql`
capability `user`, and is empty when the target declares no `sql` capability.

Every address except `TARGET_HTTP_URL` is one the kit's pods dial in the cluster: the
endpoint's Service DNS name and Service port when a file of the target declares the Service,
else the first recorded node of its role on the NodePort. `TARGET_HTTP_URL` is curled from your
machine, so it is the address the CLI reaches the Service at: the node's private address on the
NodePort on an AWS cluster (the workspace `curl` wrapper routes it through the tunnel), or a
`127.0.0.1` port-forward to the Service on a local or brought cluster, open until the phase ends.

If the target kit directory does not exist, no `TARGET_*` variables are injected. A target
`kit.yaml` that does not parse fails the phase before it runs, naming the target, the file and
the parse error (`Kit.TargetConfigInvalid`). An HTTP endpoint the CLI cannot reach (no node of
its role recorded, or a Service it cannot port-forward) fails the phase the same way
(`Kit.TargetEndpointUnreachable`); `stop` and `uninstall` report it and run without that
endpoint's variable, so a kit can always be released.

### Wiring a TARGET_* endpoint into an application pod

`TARGET_*` variables are available in the kit's shell scripts — not inside running pods.
To make the address available to an application, write a ConfigMap from the start script
and reference it in your deployment.

**In `bin/start.sh.template`:**

```bash
# Write the target endpoint into a ConfigMap the application pod reads
kubectl create configmap my-app-config \
  --from-literal=KAFKA_BOOTSTRAP_SERVERS="$TARGET_KAFKA_BOOTSTRAP" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -f my-app-deployment.yaml
```

**In `my-app-deployment.yaml`:**

```yaml
spec:
  containers:
    - name: my-app
      envFrom:
        - configMapRef:
            name: my-app-config
```

The application sees `KAFKA_BOOTSTRAP_SERVERS` (or any other variable) as a normal
environment variable. The same pattern works for any `TARGET_*` variable — just change
the key name to match what your application expects.

Delete the ConfigMap in `bin/stop.sh.template` to keep the cluster clean:

```bash
kubectl delete configmap my-app-config --ignore-not-found
```

### Wire protocol endpoint types

To expose a PostgreSQL or MySQL wire protocol port, use the corresponding endpoint type:

```yaml
endpoints:
  - name: "PostgreSQL wire"
    node-type: db
    port: 5432
    type: postgresql
    database: "mydb"      # logical database name

  - name: "MySQL wire"
    node-type: db
    port: 4000
    type: mysql
    database: "test"      # logical database name
```

The `database` field is also available on `jdbc` endpoints to store the logical database name
separately from the JDBC URL path.

### SQL capability

Database kits that expose a SQL interface should declare the `sql` capability:

```yaml
capabilities:
  - type: sql
    user: default                            # default username
    driver-class: com.clickhouse.jdbc.ClickHouseDriver  # JDBC driver (optional)
```

The `user` and `driver-class` fields are used when constructing `TARGET_JDBC_USER` and
`TARGET_JDBC_DRIVER` for bench kits targeting this database.

### Making an external kit targetable by bench kits

If you are writing an external kit that exposes a SQL interface and want bench kits like
sysbench to be able to target it, add three things to your `kit.yaml`:

**1. A `sql` capability** — declares the default username and (for JDBC) the driver class:

```yaml
capabilities:
  - type: sql
    user: root
    driver-class: com.mysql.cj.jdbc.Driver   # omit if you don't expose JDBC
```

**2. One or more wire protocol endpoints** — the endpoint type determines which `TARGET_*`
variables the bench kit receives. Declare one per protocol your database supports:

```yaml
endpoints:
  - name: "MySQL wire"
    node-type: app        # or "db" — must match the node pool your kit runs on
    port: 3306
    type: mysql
    database: "mydb"      # the logical database name bench kits should connect to

  - name: "PostgreSQL wire"
    node-type: app
    port: 5432
    type: postgresql
    database: "mydb"

  - name: "JDBC"
    node-type: app
    port: 3306
    type: jdbc
    scheme: mysql
    path: "/mydb?useSSL=false"
```

You only need to declare the protocols your database actually supports. A MySQL-compatible
database only needs the `mysql` endpoint; it does not need to also declare `jdbc` unless
you want JDBC bench tools to target it.

**3. NodePort service on the declared port** — the bench kit pod runs inside the same
Kubernetes cluster and connects via the app or db node's private IP. Make sure your kit's
`start` phase creates a NodePort service exposing the port you declared in the endpoint.

Once these three pieces are in place, a user can install sysbench (or any other bench kit
that declares `capability: sql`) against your kit:

```bash
easy-db-lab kit install sysbench --target <your-kit-name>
easy-db-lab sysbench-<your-kit-name> prepare
easy-db-lab sysbench-<your-kit-name> start
```

The capability check at install time will verify your kit exposes `sql` before writing
any files, so misconfigured targets fail immediately with a clear error.

## Required node capabilities

A built-in kit runs on any Kubernetes cluster, so its pods do not use `hostPath`, `hostPort` or
`hostNetwork` (`BuiltinKitHostDependencyTest` fails on them). A kit that cannot work without a
host path declares it in `kit.yaml`:

```yaml
requires:
  - host-path
```

The names are the node capability names `init --capability` and `status` use. `kit install`
and `<kit> start` compare them with the capabilities the cluster state records. When one is
absent, the command emits `Kit.CapabilityMissing` (the kit, the phase and the missing
capabilities, with why each is absent), exits non-zero and runs nothing. `stop` and `uninstall`
are not checked, so a kit can always be taken down.

Declaring `host-path` lets only that kit's files use `hostPath`; a `hostPort` or `hostNetwork`
in them is still a finding. `cqlite-flight` is the one built-in kit that declares it: it reads
the database's data directory off the node.

## Exposing Client Ports

A kit exposes its client ports through a **NodePort Service**, in the range 30000-32767, on a
fixed port that no other kit uses. Never use `hostPort` or `hostNetwork` for a client port:

- `hostPort` only works when the CNI chains the `portmap` plugin, which is a property of the
  datapath rather than of the kit.
- `hostNetwork` pins the pod to one host, and a port clash with another process shows up only
  as a CrashLoop.

A NodePort is reachable on every node's private IP, so declare the endpoint in `kit.yaml` with
the NodePort as its `port` and the node type that `kit info` should resolve. The existing kits
follow this: postgres serves on 30432 and clickhouse on 30123.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: mykit-nodeport
  labels:
    easydblab/kit: mykit
spec:
  type: NodePort
  selector:
    app: mykit
  ports:
    - name: client
      port: 1234
      targetPort: 1234
      nodePort: 30999   # a port no other kit uses; Hubble UI owns 31234
```

On a port-forward cluster the CLI reaches an endpoint through the Service it is published on, so
each endpoint must resolve to one. An endpoint whose NodePort a Service manifest of the kit declares
needs nothing more: the Service is found by that NodePort, whatever its name. An endpoint on a
Service the kit does not declare itself (a Helm chart's, or one an operator such as Strimzi creates)
names it in `kit.yaml`:

```yaml
endpoints:
  - name: "Kafka Bootstrap"
    node-type: db
    port: 32100
    type: kafka
    service: kafka-kafka-external-bootstrap   # the Service Strimzi creates
    service-port: 9094                        # its port behind the endpoint
```

`KitEndpointServiceTest` checks that every packaged kit's endpoints resolve this way.

## Placing pods: the role variables

A kit never names a node label. Where a role's pods run comes from the role selectors `init`
records: `type=db`, `type=app` and `type=control` on the nodes easy-db-lab builds, the
`init --role-nodes <role>=<key=value>[,...]` mapping, or no constraint at all (a cluster easy-db-lab
did not build, where every role runs on any node until it is mapped).

`--role-nodes` takes one `key=value` selector set per role: repeat the option once for each role
you map (`--role-nodes db=pool=db --role-nodes app=pool=app`). The comma-separated pairs of a
value are one set, and a node must carry every label in it. Giving a role twice is refused, and
`<role>=` with nothing after it leaves that role unconstrained.

- In a manifest template, put `nodeSelector: __DB_NODE_SELECTOR__` (or `__APP_NODE_SELECTOR__`) on
  the pod spec. It renders a JSON map, `{}` when the role is unconstrained. Never use
  `nodeAffinity` on `type`.
- In a script, use `APP_NODE_LABELS` (and `DB_NODE_LABELS`, `CONTROL_NODE_LABELS`) for
  `kubectl get nodes -l`, and only when it is not empty; `edl_require_app_node` and
  `edl_pod_overrides` already do this.

`KitRolePlacementTest` renders every kit with mapped, owned and unconstrained selectors, and
`BuiltinKitHostDependencyTest` fails on a literal `type=app` or `type: db`.

## Client networks

A passwordless database (ClickHouse's `default` user, Postgres's `pg_hba` trust lines) accepts
clients only from the environment's networks: the VPC CIDR and the K3s cluster CIDR
(`Constants.K3s.CLUSTER_CIDR`, passed to K3s as `--cluster-cidr`) on the nodes easy-db-lab builds,
each node's pod CIDR and InternalIP on a cluster it did not build (every node's InternalIP, in a
role or not, since NodePort traffic can be SNATed from any node), plus every
`init --client-network <cidr>`. A local workspace (`init --local`) has only its `--client-network`
values; with none recorded, kit variables fail, naming the option, rather than trust the VPC default. Templates take `__CLIENT_NETWORKS_JSON__` (a JSON list of CIDRs)
and `__CLIENT_NETWORKS_HBA_JSON__` (one `host all all <cidr> trust` line each). Never hard-code
`10.0.0.0/8`.

## Adding a New Kit

1. Create `src/main/resources/com/rustyrazorblade/easydblab/kits/<name>/kit.yaml`
2. Add lifecycle steps — start with `start` and `stop` at minimum
3. Add a `metrics` block if the kit exposes Prometheus metrics
4. Add a `METRICS.md` documenting the available metrics
5. Create `dashboards/<name>.json` with panels for the key metrics
6. Run `easy-db-lab kit install <name>` to scaffold the working directory
7. Test `<name> start` and `<name> stop` against a real cluster

No Kotlin code is required. The install and kit runner commands register dynamically from
the `kit.yaml` files at startup.
