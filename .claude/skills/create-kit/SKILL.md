---
name: create-kit
description: Interactively generate a new kit (kit.yaml with typed lifecycle steps). Researches the workload's helm chart, CRDs, and readiness conditions, proposes a draft for review, refines with user feedback, and writes files only on explicit approval.
user-invocable: true
---

# create-kit

Generate a new kit for easy-db-lab: a `kit.yaml` with typed lifecycle steps, in
`src/main/resources/com/rustyrazorblade/easydblab/kits/<name>/`. The full reference is
`docs/development/kits.md`.

## Input

The argument after `/create-kit` is the workload name (e.g. "ScyllaDB", "Kafka", "Redis").
If no argument is provided, ask the user what workload they want to add.

---

## Step 1 — Research the workload

Use WebSearch and/or context7 to look up:
- The canonical Helm chart (repo URL, chart name, current stable version)
- Any operator or CRD that must be installed first (e.g. an operator chart before the CR)
- The primary Kubernetes resource kind created by the chart (e.g. `StatefulSet`, `Deployment`, a custom CR)
- The recommended readiness condition to wait on (pod labels, conditions)
- Common configuration values (replicas, storage size, node affinity)
- Whether the workload is stateful (needs `platform-pvs`) or stateless

Also research:
- **Metrics endpoint**: Does the workload expose a Prometheus metrics endpoint? If yes, what port and path (e.g. port 9363, path `/metrics`)?
- **JVM-based?**: Is the workload written in Java/JVM? If yes, it needs Pyroscope Java agent injection in the `start` phase (see profiling section below) in addition to any OTel metrics setup.
- **Helm-native OTLP?**: Does the helm chart have built-in OpenTelemetry or OTLP export support (configurable via values)? If yes, it may be a `helm-native` candidate.

Summarize findings in 3–5 bullet points before proposing, including the recommended `metrics` mode and whether Pyroscope agent injection is needed.

---

## Step 2 — Propose a draft kit.yaml

Using the research, generate a draft `kit.yaml`. Present it to the user as a code block **before writing any files**. Explain each phase briefly.

### kit.yaml format

```yaml
name: <kebab-case-name>
type: db                       # db | app: the node pool the kit runs on
description: <one-line description>
version: "<chart-version>"
collision-check: true          # refuse a second install and a start while running
metrics:                       # a list; omit if the workload has no metrics
  - type: scrape               # scrape | java-agent | helm-native
    port: 9363                 # required for scrape
    path: /metrics             # default /metrics
    pod-selector: "app=<name>" # scrape the pods directly (preferred)
runtime:                       # how status and the start collision check find the running pods
  type: pods
  selector: "app=<name>"
  namespace: default
endpoints:
  - name: "<Name>"
    node-type: db
    port: 30xxx                # a NodePort in 30000-32767
    type: native               # http | https | jdbc | native | cql | postgresql | mysql | kafka
args:
  - flag: --size
    variable: STORAGE_SIZE
    description: Storage size per node (e.g. 100Gi)
    required: true
    type: string
  - flag: --replicas
    variable: REPLICAS
    description: Number of replicas
    type: int
    default: "${DB_NODE_COUNT}"

install:
  - type: helm-repo
    name: <repo-alias>
    url: <repo-url>
  - type: helm
    chart: <repo-alias>/<chart-name>
    release: <release-name>
    namespace: <namespace>
    version: "<chart-version>"
    values:
      key: value
  - type: platform-pvs      # include only if stateful (needs local storage)
    node-type: db

start:
  - type: namespace
    name: <namespace>
  - type: manifest           # include if there's a custom CR to apply
    template: <name>.yaml      # the installed copy of <name>.yaml.template
  - type: wait
    kind: <Kind>
    name: <resource-name>
    namespace: <namespace>
    condition: Available
    timeout: 300s

stop:
  - type: delete
    kind: <Kind>
    name: <resource-name>
    namespace: <namespace>

uninstall:
  - type: helm-uninstall
    release: <release-name>
    namespace: <namespace>
```

### `metrics` block

The optional `metrics` list declares how the workload's telemetry reaches the OTel collector
(`KitMetrics` in `services/KitConfig.kt`). Three entry types:

```yaml
metrics:
  # Prometheus endpoint, scraped by the collector DaemonSet. With pod-selector the collector
  # finds the pods by label and scrapes each on its pod IP and container port, from its own node.
  - type: scrape
    port: 9363
    path: /metrics
    job: my-workload          # optional, default the kit name; unique within the kit
    pod-selector: "app=my-workload"

  # JVM workload: the OTel Java agent, mounted from the host at /usr/local/otel, pushes OTLP to
  # the collector on its node at http://$(HOST_IP):4318. See kits/neo4j/statefulset.yaml.template.
  - type: java-agent
    service-name: my-workload

  # Built-in telemetry configured through helm values; no collector change needed.
  - type: helm-native
```

A `scrape` entry without `pod-selector` is scraped at `localhost:<port>` on every node, which only
works through a NodePort or a `hostPort`. Prefer `pod-selector` (see `kits/memcached/kit.yaml`).

### Available step types

| Type | Purpose |
|---|---|
| `helm-repo` | Add/update a Helm chart repo (`name`, `url`) |
| `helm` | Install or upgrade a Helm release (`chart`, `release`, `namespace`, `version?`, `values?`, `values-file?`) |
| `helm-uninstall` | Remove a Helm release (`release`, `namespace`) |
| `namespace` | Create a namespace idempotently (`name`) |
| `manifest` | Apply an installed manifest from the kit directory (`template`, `interpolate?`) |
| `manifest-url` | Apply a manifest from a remote URL (`url`) |
| `kustomize` | Apply a kustomize overlay (`url`) |
| `wait` | Wait for a K8s resource condition (`kind`, `name`, `namespace?`, `condition`, `timeout`) |
| `delete` | Delete a K8s resource (`kind`, `name`, `namespace?`, `ignore-not-found?`) |
| `platform-pvs` | Create local PVs via platform service (`node-type`, `count?`, `volume-claim-template-name?`, `storage-class?`) |
| `platform-pvs-delete` | Delete the kit's local PVs and data directories (`node-type`) |
| `configmap` | Create or update a ConfigMap (`name`, `namespace`, `data`) |
| `label` | Add labels to cluster nodes (`node-type`, `labels`) |
| `exec` | Run a command inside a running pod (`pod`, `namespace`, `command`) |
| `shell` | Execute a shell command with cluster env vars injected (`script`) |

### Variable interpolation

String fields in `kit.yaml` steps support `${VAR}` substitution when the phase runs, and shell
steps get the same values as environment variables (`TemplateVariables` in `services/`):
- `${CLUSTER_NAME}` — cluster name
- `${CONTROL_HOST_PRIVATE}` — control node private IP
- `${DB_NODE_COUNT}`, `${APP_NODE_COUNT}` — node counts
- `${DB_NODE_IPS}`, `${APP_NODE_IPS}` — comma-separated private IPs
- `${KIT_NAME}` — the installed kit instance name
- Any arg variable declared in `args` (e.g. `${REPLICAS}`, `${STORAGE_SIZE}`)

`.template` files use `__VAR__` instead (e.g. `__MEMORY_MB__`), substituted once by `kit install`.
Never write a literal double underscore in a `.template` file.

---

## Step 3 — Refine interactively

After presenting the draft:
- Ask if the user wants any changes
- Accept feedback (e.g. "add TLS option", "default to 3 nodes", "use kube-system namespace")
- Update the draft and re-present
- Repeat until the user explicitly approves

**Do not write any files until the user says the draft looks good.**

---

## Step 4 — Write files on approval

Once the user approves, write the following files:

### Required

```
src/main/resources/com/rustyrazorblade/easydblab/kits/<name>/kit.yaml
```

### If the kit.yaml references `type: manifest` steps

Generate starter template files for each referenced template. For example, if `start` contains:

```yaml
- type: manifest
  template: scylladb.yaml
```

Write a starter `scylladb.yaml.template` using known field values from the workload's CRD schema. Include `__VAR__` substitutions for configurable values like replica count, storage size, cluster name. `kit install` renders it into the kit directory as `scylladb.yaml`, which the step applies.

Template files go alongside `kit.yaml`:
```
src/main/resources/com/rustyrazorblade/easydblab/kits/<name>/scylladb.yaml.template
```

### After writing

Print this checklist:

```
Files written:
  src/main/resources/.../kits/<name>/kit.yaml
  src/main/resources/.../kits/<name>/<template>.yaml.template  (if any)

Next steps:
  [ ] Run: easy-db-lab kit list   (verify the kit appears)
  [ ] Add --<name> flag to bin/end-to-end-test and validate on a live cluster before merging
```

---

## Step 5 — Generate metrics artifacts (scrape-type workloads only)

If the kit declares a `scrape` metrics entry, three additional files SHOULD be committed alongside `kit.yaml`. These are **not generated at runtime** — they are authored once from real data on a live cluster and committed.

### Workflow

1. **Install and start the workload** on a live cluster so metrics are flowing to VictoriaMetrics.
2. **Export the catalog.** The script sources `env.sh`, so run it from the cluster workspace (the directory holding `env.sh`), calling it by its path in the checkout:
   ```bash
   cd <cluster-workspace>
   <checkout>/bin/export-workload-metrics <name>
   ```
   This writes `<cluster-workspace>/<name>/metrics-catalog.json` (workload name, export timestamp, a `common_labels` map of the label keys present on every series, and a `series` array with one entry per metric name whose `labels` holds only that metric's other label keys; each key maps to its sorted distinct values, at most 20 per key, and per-pod identity labels are dropped) — not the kit's resource directory. That `<name>/` directory is deleted by `<name> uninstall`, so copy the file into `src/main/resources/com/rustyrazorblade/easydblab/kits/<name>/metrics-catalog.json` before uninstalling.
3. **Author `METRICS.md`** — a machine-readable markdown table of the key metrics. Copy the file to the workload's resource directory. Format:
   ```markdown
   # <WorkloadName> Metrics

   ## Key Metrics

   | Metric | Labels | Description |
   |--------|--------|-------------|
   | <metric_name> | <key=val> | What this metric measures |
   ```
   Only include metrics that are genuinely useful for diagnosing workload health. All metric names MUST appear in `metrics-catalog.json`.
4. **Author `dashboards/<name>.json`** — a Grafana dashboard JSON built from metric names in `METRICS.md`. Requirements:
   - Include a `cluster` multi-select template variable querying `label_values(up, cluster)` against the VictoriaMetrics datasource
   - Scope all panel queries with `{cluster=~"$cluster",job="<name>"}`
   - Use the VictoriaMetrics datasource UID `"VictoriaMetrics"`
5. **Copy all three** into the workload's resource directory:
   ```
   src/main/resources/.../kits/<name>/metrics-catalog.json
   src/main/resources/.../kits/<name>/METRICS.md
   src/main/resources/.../kits/<name>/dashboards/<name>.json
   ```
   The dashboard is auto-installed by easy-db-lab when `start` completes.

### After writing metrics artifacts

Append to the checklist printed in Step 4:
```
  [ ] Verify metrics-catalog.json has ≥10 series entries, one per metric name (should be much more)
  [ ] Review METRICS.md — all metric names must match catalog
  [ ] Open Grafana and verify the dashboard loads with real data
```

---

---

## Profiling (JVM workloads)

All cluster nodes have the Pyroscope Java agent pre-installed at `/usr/local/pyroscope/pyroscope.jar`.
For any JVM workload, the `start` phase **must** inject the agent into every JVM container.

The pattern is a `kubectl patch` using `JAVA_TOOL_OPTIONS` + a `hostPath` volume mount. Use
`component=<role>` labels to distinguish multiple JVM processes (e.g. coordinator vs worker).

Full implementation pattern and explanation: `docs/development/kits.md` (Profiling section).
User-facing documentation on what profiles are available and how to access them in Grafana:
`docs/user-guide/profiling.md`.

### What to add to `start` phase scripts for a JVM workload

```bash
PYROSCOPE_OPTS="-javaagent:/usr/local/pyroscope/pyroscope.jar \
  -Dpyroscope.application.name=<workload> \
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

Run this patch for each JVM deployment (coordinator, worker, broker, etc.) before the rollout
wait. `CONTROL_HOST_PRIVATE` and `CLUSTER_NAME` are injected as environment variables at runtime —
use them directly as shell variables, not as `__VAR__` template substitutions.

---

## Guardrails

- **Never write files before explicit user approval.** "That looks good" or "go ahead" counts as approval. "What do you think?" does not.
- **Only generate kit.yaml** and referenced `.template` files — no shell scripts, no `bin/` directory.
- **Use typed steps only.** No `type: shell` unless the user specifically asks for an escape hatch.
- **Keep args minimal.** Only expose flags the user would actually tune at install time.
- **Prefer `wait` over `shell` for readiness checks.** Only use `shell` for `kubectl wait` with label selectors (e.g. `clickhouse.altinity.com/chi=`) that can't be expressed with `kind`/`name`.
- **Verify the generated `kit.yaml` is parseable** before reporting completion — mentally trace through the schema to catch typos in step types or missing required fields.
- **Metrics registration is automatic.** For each `scrape` entry, easy-db-lab automatically creates the metrics ConfigMap and syncs the OTel collector after a successful `start`, and removes it after `stop`. Do NOT add `type: configmap` or `type: sync-otel` steps for this — they are handled by the runtime.
- **Always include a `metrics` block** if the workload exposes metrics in any form. If unsure, research the workload's observability docs before proposing.
- **Scrape with a `pod-selector`, not a `hostPort`.** A `pod-selector` lets the collector reach the metrics port on the pod IP. Client ports go through a NodePort Service (30000-32767), never `hostPort` or `hostNetwork`.
