---
name: generate-workload
description: Interactively generate a new workload install.yaml with typed lifecycle steps. Researches the workload's helm chart, CRDs, and readiness conditions, proposes a draft for review, refines with user feedback, and writes files only on explicit approval.
user-invocable: true
---

# generate-workload

Generate a new workload definition for easy-db-lab using the typed install.yaml format.

## Input

The argument after `/generate-workload` is the workload name (e.g. "ScyllaDB", "Kafka", "Redis").
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

Summarize findings in 3–5 bullet points before proposing.

---

## Step 2 — Propose a draft install.yaml

Using the research, generate a draft `install.yaml`. Present it to the user as a code block **before writing any files**. Explain each phase briefly.

### install.yaml format

```yaml
name: <kebab-case-name>
description: <one-line description>
version: "<chart-version>"
collision-check: true          # true if start phase should guard against double-start
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
    template: <name>.yaml.template
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

### Available step types

| Type | Purpose |
|---|---|
| `helm-repo` | Add/update a Helm chart repo (`name`, `url`) |
| `helm` | Install or upgrade a Helm release (`chart`, `release`, `namespace`, `version?`, `values?`, `values-file?`) |
| `helm-uninstall` | Remove a Helm release (`release`, `namespace`) |
| `namespace` | Create a namespace idempotently (`name`) |
| `manifest` | Render a `.template` file and apply via K8s API (`template`) |
| `manifest-url` | Apply a manifest from a remote URL (`url`) |
| `kustomize` | Apply a kustomize overlay (`url`) |
| `wait` | Wait for a K8s resource condition (`kind`, `name`, `namespace?`, `condition`, `timeout`) |
| `delete` | Delete a K8s resource (`kind`, `name`, `namespace?`, `ignore-not-found?`) |
| `platform-pvs` | Create local PVs via platform service (`node-type`, `count?`) |
| `configmap` | Create or update a ConfigMap (`name`, `namespace`, `data`) |
| `label` | Add labels to cluster nodes (`node-type`, `labels`) |
| `exec` | Run a command inside a running pod (`pod`, `namespace`, `command`) |
| `shell` | Execute a shell command with cluster env vars injected (`script`) |

### Variable interpolation

String fields support `${VAR}` substitution from cluster state:
- `${CLUSTER_NAME}` — cluster name
- `${CONTROL_NODE_IP}` — control node private IP
- `${DB_NODE_COUNT}` — number of db nodes
- `${APP_NODE_COUNT}` — number of app nodes
- Any arg variable declared in `args` (e.g. `${REPLICAS}`, `${STORAGE_SIZE}`)

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
src/main/resources/com/rustyrazorblade/easydblab/install/<name>/install.yaml
```

### If the install.yaml references `type: manifest` steps

Generate starter template files for each referenced template. For example, if `start` contains:

```yaml
- type: manifest
  template: scylladb.yaml.template
```

Write a starter `scylladb.yaml.template` using known field values from the workload's CRD schema. Include `${VAR}` substitutions for configurable values like replica count, storage size, cluster name.

Template files go alongside `install.yaml`:
```
src/main/resources/com/rustyrazorblade/easydblab/install/<name>/scylladb.yaml.template
```

### After writing

Print this checklist:

```
Files written:
  src/main/resources/.../install/<name>/install.yaml
  src/main/resources/.../install/<name>/<template>.yaml.template  (if any)

Next steps:
  [ ] Run: easy-db-lab install --list   (verify the workload appears)
  [ ] Add --<name> flag to bin/end-to-end-test and validate on a live cluster before merging
```

---

## Guardrails

- **Never write files before explicit user approval.** "That looks good" or "go ahead" counts as approval. "What do you think?" does not.
- **Only generate install.yaml** and referenced `.template` files — no shell scripts, no `bin/` directory.
- **Use typed steps only.** No `type: shell` unless the user specifically asks for an escape hatch.
- **Keep args minimal.** Only expose flags the user would actually tune at install time.
- **Prefer `wait` over `shell` for readiness checks.** Only use `shell` for `kubectl wait` with label selectors (e.g. `clickhouse.altinity.com/chi=`) that can't be expressed with `kind`/`name`.
- **Verify the generated `install.yaml` is parseable** before reporting completion — mentally trace through the schema to catch typos in step types or missing required fields.
