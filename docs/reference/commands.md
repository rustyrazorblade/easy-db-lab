# Command Reference

Complete reference for all easy-db-lab commands.

## Global Options

| Option | Description |
|--------|-------------|
| `--help`, `-h` | Shows help information |
| `--vpc-id` | Reconstruct state from existing VPC (requires ClusterId tag) |
| `--force` | Force state reconstruction even if state.json exists |

---

## Profile Commands

Profile inspection and setup. Not to be confused with `cassandra profile`, which controls runtime
profiling of a running cluster.

### profile

Parent command for the profile group. Run without a subcommand to list what is available.

```bash
easy-db-lab profile
```

### profile show

Report the active profile: its name, its directory, and the settings it holds.

```bash
easy-db-lab profile show
```

Reads only the profile directory, so it runs from anywhere — no cluster workspace needed. Secret
values are never printed: AxonOps and Tailscale are reported as `ENABLED` or `DISABLED`, and the
AWS access key and secret are not shown at all.

The profile reported is the one named by `EASY_DB_LAB_PROFILE`, or `default` when that is unset:

```bash
EASY_DB_LAB_PROFILE=staging easy-db-lab profile show
```

If the profile has no `settings.yaml`, the command says so and points at `easy-db-lab profile
setup`. If the file is present but unreadable, it reports that instead, naming the file.

### profile setup

Set up user profile interactively.

```bash
easy-db-lab profile setup
```

Guides you through:

- Email and AWS credentials collection
- AWS credential validation
- Key pair generation
- IAM role creation
- Packer VPC infrastructure setup
- AMI validation/building

### show-iam-policies

Display IAM policies with your account ID populated.

```bash
easy-db-lab show-iam-policies [policy-name]
```

**Aliases:** `sip`

| Argument | Description |
|----------|-------------|
| `policy-name` | Optional filter: `ec2`, `iam`, or `emr` |

### build-image

Build both base and Cassandra AMI images.

```bash
easy-db-lab build-image [options]
```

| Option | Description | Default |
|--------|-------------|---------|
| `--arch` | CPU architecture (AMD64, ARM64) | AMD64 |
| `--region` | AWS region | (from profile) |

---

## Cluster Lifecycle Commands

### init

Initialize a directory for easy-db-lab.

```bash
easy-db-lab init [cluster-name] [options]
```

The database and application node groups are configured through a namespaced
`--db.*` / `--app.*` scheme. Every pre-existing flag continues to work as an
**alias** carrying its established default. When both a namespaced option and its
legacy alias are supplied for the same setting, **the namespaced option always
wins**, regardless of the order they appear on the command line.

Architecture is no longer a flag. Each node group's CPU architecture is derived
automatically from that group's resolved instance type at `init` time (via the
EC2 `DescribeInstanceTypes` `SupportedArchitectures` field) and persisted per
group in cluster state. A cluster whose database and application groups have
different architectures is provisioned correctly, each group booting from the
AMI for its own architecture. An instance type whose architecture cannot be
determined fails at `init`, before any instance is created.

| Option | Description | Default |
|--------|-------------|---------|
| `--db.count` (alias `--db`, `--cassandra`, `-c`) | Number of database instances | 3 |
| `--app.count` (alias `--app`, `--stress`, `-s`) | Number of application instances | 0 |
| `--db.instance-type` (alias `--instance`, `-i`) | Database instance type | i4i.xlarge |
| `--app.instance-type` (alias `--stress-instance`, `-si`) | Application instance type | c6id.2xlarge |
| `--azs`, `-z` | Availability zones (e.g., `a,b,c`) | all |
| `--ebs.type` | EBS volume type (NONE, gp2, gp3, io1, io2) | NONE |
| `--ebs.size` | EBS volume size in GB | 256 |
| `--ebs.iops` | EBS IOPS (gp3 only) | 0 |
| `--ebs.throughput` | EBS throughput (gp3 only) | 0 |
| `--ebs.optimized` | Enable EBS optimization | false |
| `--until` | When instances can be deleted | tomorrow |
| `--ami` | Override AMI ID | (auto-detected) |
| `--open` | Unrestricted SSH access | false |
| `--tag` | Custom tags (key=value, repeatable) | - |
| `--vpc` | Use existing VPC ID | - |
| `--cni` | Pod-network CNI: `flannel` (K3s built-in overlay) or `cilium` (ENI native routing). See [Pod Networking (CNI)](../user-guide/networking.md) | cilium |
| `--tenant` | Observability tenant the cluster's traces, profiles, metrics, logs and annotations belong to. Must match `^[a-z][a-z0-9_-]{0,62}$`; checked before anything is created. Fixed for the life of the cluster. See [Where observability data is stored](../user-guide/monitoring.md#where-observability-data-is-stored) | default |
| `--up` | Auto-provision after init | false |
| `--clean` | Remove existing config first | false |

### up

Provision AWS infrastructure.

```bash
easy-db-lab up [options]
```

| Option | Description |
|--------|-------------|
| `--no-setup`, `-n` | Skip K3s setup and AxonOps configuration |

Creates: VPC, EC2 instances, K3s cluster. Configures the account S3 bucket for this cluster.

`up` fails fast. If any provisioning step fails — EC2 setup, K3s, node labeling, the
`local-storage`/`local-storage-wfc` StorageClasses, the observability stack, Tailscale, and so
on — the command exits non-zero and stops rather than continuing with a partially-provisioned
cluster. EC2 instances that were already launched are left running; there is no automatic
rollback. Reclaim them with `easy-db-lab down`, fix the underlying issue, and re-run `up`.

### down

Shut down AWS infrastructure.

```bash
easy-db-lab down [vpc-id] [options]
```

| Argument | Description |
|----------|-------------|
| `vpc-id` | Optional: specific VPC to tear down |

| Option | Description |
|--------|-------------|
| `--all` | Tear down all VPCs tagged with easy_cass_lab |
| `--packer` | Tear down the packer infrastructure VPC |
| `--force` | Skip the pre-teardown flush and backup, and tear down anyway |

**Flush before teardown.** When you tear down the current cluster, `down` first previews the resources and asks for confirmation. The flush stops Loki and Mimir, so it runs only once the teardown is going ahead: a declined prompt, `--dry-run`, or a VPC with nothing left in it stops no backend. Then `down` saves everything the cluster holds that is not yet in S3, before it removes any infrastructure. In order:

1. Every Grafana annotation is mirrored to Loki. Loki accepts only entries from the last 8760 hours to 24 hours ahead; an annotation outside that window is skipped with a warning naming its id, and it stays in the annotations backup.
2. Loki is flushed: its ingester is stopped, which writes every open chunk to S3, and each index file it built is checked in S3. If the shutdown wrote chunks but no index file is found on the control node, or the node cannot list its index, the flush fails rather than passing with nothing checked.
3. Mimir is flushed: its ingester is stopped, which cuts and ships every block it holds, and each block is checked in S3.
4. The Grafana annotations are backed up to `observability/annotations/<tenant>/` in the account bucket.

Every step has a timeout. A redirect cluster has no local backends, so it skips these steps.

**Stop on failure.** If a step fails or times out, `down` stops there. It removes no infrastructure, because tearing down would destroy the data that is not yet in S3. It does not start Loki or Mimir again, retry the step, or undo anything: each backend stays as the failed step left it, and its write-ahead log and local blocks stay on the control node's disk. The report names the step that failed and why, and the state of each backend: running, ingester stopped (the pod still runs but takes no new data), not ready, or scaled to 0. `down` exits with a non-zero status. If both backends are still running, fix the cause and run `down` again. If a backend is stopped, `down` cannot flush it without starting it again, which it never does, so `down --force` is the way to finish.

**A re-run after a successful flush skips it.** A flush that succeeds is recorded in the cluster state, with the time it completed and what it verified. If removing the infrastructure then fails, `down` reports the failure and restores nothing: Loki and Mimir stay at 0. Run `down` again and it skips the flush, says when the earlier one completed, and goes straight to the teardown. `up` clears the record, so the next `down` flushes the new data.

**A re-run after a failed flush stops early.** With no successful flush recorded, `down` checks that Loki and Mimir are both running before it touches anything. If either is scaled to 0 or not ready, `down` stops, says that its data cannot be flushed without starting it again, and points you at `down --force`.

**Nothing is deleted.** `down` sets no S3 lifecycle, expiry or retention rule on any bucket and deletes no object that holds your data. With `--all`, a per-cluster data bucket is deleted only when it is already empty. A data bucket that still holds objects is left as it is, and `down` reports it as kept, with S3's reason.

**`--force` skips them.** Pass `--force` to skip the flush and the backup and tear down anyway, without the logs and metrics not yet in S3. Use it only when the backends are already stopped or gone, or when you do not need the data.

**Exit status.** `down` exits 0 only when the teardown succeeds. It exits non-zero when a flush step stops it, when the teardown completes with errors, and when you decline the confirmation prompt.

### clean

Clean up generated files from the current directory.

```bash
easy-db-lab clean
```

### hosts

List all hosts in the cluster.

```bash
easy-db-lab hosts
```

### status

Display full environment status.

```bash
easy-db-lab status
```

`status` is the one command that degrades instead of failing outright when the SOCKS proxy
tunnel can't be established. It still reports EC2, VPC, security groups, Spark/EMR, OpenSearch,
S3, kits, observability URLs, and database versions — the last read directly over SSH, which
never uses the tunnel. Only the sections that require the private Kubernetes API (stress jobs,
ClickHouse) are marked unavailable, each stating the proxy failure as the reason. `status` still
exits non-zero when degraded, so a partial report is never mistaken for a healthy cluster by a
script. See [Network Connectivity](../user-guide/network-connectivity.md) for how to diagnose a
tunnel that won't come up.

---

## Cassandra Commands

All Cassandra commands are available under the `cassandra` subcommand group.

### cassandra use

Select a Cassandra version.

```bash
easy-db-lab cassandra use <version> [options]
```

| Option | Description |
|--------|-------------|
| `--java` | Java version to use |
| `--hosts` | Filter to specific hosts |

Versions: 3.0, 3.11, 4.0, 4.1, 5.0, 5.0-HEAD, 6.0-HEAD, trunk

Fails if the version is not installed on a targeted node — install it first with
`cassandra install`.

### cassandra build

Build a Cassandra branch checkout on your own machine and publish it to the profile's S3 bucket,
where `cassandra install` can find it by name.

```bash
easy-db-lab cassandra build [<dir>] --java <N> [options]
```

| Option | Description | Default |
|--------|-------------|---------|
| `<dir>` | Cassandra source checkout to build | current directory |
| `--java`, `-j` | JDK major version to build with. Required — it forms part of the build's name | — |
| `--jira` | Ticket this build is for, e.g. `CASSANDRA-19000` | none |
| `--name` | Label folded into the name, to tell your builds apart at a glance | none |
| `--ant-flags` | Extra flags passed to ant | none |

The build runs `ant realclean` then `ant artifacts` in the checkout, under the JDK you name.
`JAVA_HOME` is set for ant only — your shell's default JDK is untouched.

The version comes from the `base.version` property in the checkout's `build.xml`. Nothing is ever
inferred from the branch name — not the version, not the ticket. A branch naming convention is a
habit, not a contract, and this name is permanent once published, so `--jira` and `--name` are the
only ways anything gets into it. The result is named for what identifies it:

```
5.1-CASSANDRA-19000-flushfix-20260905-a1b2c3d-jdk17
<version>-<JIRA>------<--name>--<date>--<sha>--<jdk>
```

The ticket and `--name` segments are dropped entirely when absent, giving
`5.1-20260905-a1b2c3d-jdk17`. `--name` is for the case the rest of the name cannot help with: two
builds of the same commit that differ in something only you know. It sits after the ticket so the
version stays the leading segment and a bucket listing still sorts by release. Letters, digits,
`.`, `_` and `-` only, 40 characters or fewer — anything else is refused rather than quietly
rewritten, because this name is the build's identity in S3 and on every node.
That name is the build's S3 directory, the directory it installs into on a node, and the name
`cassandra install` takes.

A checkout with uncommitted changes builds normally — that is the case this command exists for —
but you are warned, because the sha then does not fully describe what was built, and the manifest
records the tree as dirty.

No cluster is needed. Builds belong to the profile, so one made on your desktop installs from your
laptop.

**Published layout**, under the profile's account bucket:

```
cassandra-builds/5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17/
    apache-cassandra-5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17-bin.tar.gz
    manifest.json
```

`manifest.json` records the base version, the full and short sha, the branch and remote, whether
the tree was dirty, the ticket, the ant flags, when it was built and by which profile, and the
tarball's size and SHA-256.

Then install it on a cluster:

```bash
easy-db-lab cassandra install 5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17
```

Nodes fetch the tarball straight from the bucket with their instance profile.

### cassandra install

Install an additional Cassandra version onto a running cluster, without rebuilding the AMI.

```bash
easy-db-lab cassandra install <version> [options]
```

| Option | Description | Default |
|--------|-------------|---------|
| `--url` | Tarball URL (`.tar.gz`) or git repository URL (with `--branch`) | from the declared entry |
| `--branch` | Git branch to clone and build, requires `--url` | from the declared entry |
| `--java`, `-j` | Java version to build and run this version with | from the declared entry |
| `--python` | Python version cqlsh runs under | `3.11.9` |
| `--ant-flags` | Extra flags passed to ant when building from a branch | from the declared entry |
| `--hosts` | Filter to specific hosts | all Cassandra nodes |

Each option falls back to the version's `cassandra_versions.yaml` entry when not supplied, so a
declared version needs no options at all. A name that is not declared locally is looked up among
the builds published by [`cassandra build`](#cassandra-build), so a build installs by name with no
options either. Every targeted node is attempted regardless of what
happens on the others, and each node's outcome is reported individually.

A version already installed on a node is never rebuilt:

- Asking for the parameters it was built with is a no-op.
- Asking for different `--java`, `--python`, or `--ant-flags` changes nothing on that node, reports
  which fields disagree, and exits non-zero. To run the existing build under a different JDK, use
  `cassandra use <version> --java <version>` instead.

A failure on any node also exits non-zero, with the reason reported against that node. A token
embedded in `--url` is used for the clone but never persisted to the node and never appears in logs,
errors, or events.

See [Configuring Cassandra](../user-guide/installing-cassandra.md#custom-builds) for the full
workflow.

### cassandra write-config

Generate a new configuration patch file.

```bash
easy-db-lab cassandra write-config [filename] [options]
```

**Aliases:** `wc`

| Option | Description | Default |
|--------|-------------|---------|
| `-t`, `--tokens` | Number of tokens | 4 |

### cassandra update-config

Apply configuration patch to all nodes.

```bash
easy-db-lab cassandra update-config [options]
```

**Aliases:** `uc`

| Option | Description |
|--------|-------------|
| `--restart`, `-r` | Restart Cassandra after applying |
| `--hosts` | Filter to specific hosts |

### cassandra download-config

Download configuration files from nodes.

```bash
easy-db-lab cassandra download-config [options]
```

**Aliases:** `dc`

| Option | Description |
|--------|-------------|
| `--version` | Version to download config for |

### cassandra start

Start Cassandra on all nodes.

```bash
easy-db-lab cassandra start [options]
```

| Option | Description | Default |
|--------|-------------|---------|
| `--sleep` | Time between starts in seconds | 120 |
| `--hosts` | Filter to specific hosts | - |
| `--sidecar-image` | Container image for the sidecar DaemonSet | `ghcr.io/apache/cassandra-sidecar:latest` |

Use `--sidecar-image` to test a fork or specific version:

```bash
easy-db-lab cassandra start --sidecar-image ghcr.io/myfork/cassandra-sidecar:my-branch
```

### cassandra stop

Stop Cassandra on all nodes.

```bash
easy-db-lab cassandra stop [options]
```

| Option | Description |
|--------|-------------|
| `--hosts` | Filter to specific hosts |

### cassandra restart

Restart Cassandra on all nodes.

```bash
easy-db-lab cassandra restart [options]
```

| Option | Description |
|--------|-------------|
| `--hosts` | Filter to specific hosts |

### cassandra list

List available Cassandra versions.

```bash
easy-db-lab cassandra list
```

**Aliases:** `ls`

Versions installed on the node are listed first. A version declared with `lazy: true` that is not
installed on that node is listed too, marked `(declared, not installed)`, as is any build published
by [`cassandra build`](#cassandra-build) and not yet installed there, marked
`(build, not installed)`.

---

## Cassandra Profiling Commands

Runtime async-profiler control under `cassandra profile`. Every command applies to all Cassandra
nodes by default; `--hosts` narrows it to a subset. A cluster profiles CPU automatically from
cluster-up, so these are for changing what is profiled and for pulling profiles out.

This is not the top-level `profile` group, which manages your easy-db-lab user profile. See
[Profile Commands](#profile-commands) for that.

See [Profiling](../user-guide/profiling.md) for the full guide.

### cassandra profile start

Enable profiling with a given set of async-profiler arguments. Arguments after `--` are passed to
`asprof` untouched.

```bash
easy-db-lab cassandra profile start -- -e cpu
easy-db-lab cassandra profile start --loop 30s -- -e wall -i 10ms
easy-db-lab cassandra profile start --hosts db0,db1 -- -e cpu --alloc 512k
```

| Option | Default | Description |
|--------|---------|-------------|
| `--loop` | `1m` | JFR rotation interval |
| `--retention` | `60` | Minutes of profile data to keep on each node |
| `--max-bytes` | `2147483648` | Byte ceiling for each node's profile directory |
| `--hosts` | all | Comma-separated host aliases |

easy-db-lab supplies async-profiler's output file, output format, rotation, and session duration, so
`-f`/`--file`, `-o`/`--output`, bare format words, `--loop`, `-d`/`--duration` and `--timeout` are
rejected at the CLI before any node is contacted. Use `--loop` above to set rotation.

```admonish warning
Never combine a CPU event with wall-clock sampling in one recording. Switch modes with `stop`/`start`
instead — see [the cpu+wall hazard](../user-guide/profiling.md#the-cpuwall-hazard).
```

### cassandra profile stop

Disable profiling. Records an explicit disabled state; the running session is stopped cleanly so its
in-flight chunk is finalized and still ships.

```bash
easy-db-lab cassandra profile stop
easy-db-lab cassandra profile stop --hosts db0
```

### cassandra profile status

Report per node: enabled state, the process being profiled, session age, your arguments verbatim, the
full command line as actually invoked, chunks pending/shipped/rejected, bytes on disk, and the last
shipping error.

```bash
easy-db-lab cassandra profile status
```

### cassandra profile fetch

Download completed JFR chunks into `./profiles/<host>/`. The chunk currently being written is never
offered — it has no constant pool yet and no tool can read it.

```bash
easy-db-lab cassandra profile fetch
easy-db-lab cassandra profile fetch --last 20 --hosts db0
```

| Option | Default | Description |
|--------|---------|-------------|
| `--last` | `5` | How many of the most recent completed chunks to download |
| `--hosts` | all | Comma-separated host aliases |

### cassandra profile flamegraph

Convert recent chunks into a flame graph on the node and download the result. `jfrconv` arguments
after `--` pass through untouched; its input and output are reserved.

```bash
easy-db-lab cassandra profile flamegraph --last 10
easy-db-lab cassandra profile flamegraph --last 20 -- --threads
```

| Option | Default | Description |
|--------|---------|-------------|
| `--last` | `5` | How many of the most recent completed chunks to convert |
| `--format` | `html` | Output format handed to `jfrconv` |
| `--hosts` | all | Comma-separated host aliases |

`--threads` is the only way to get Cassandra thread-pool attribution: Pyroscope's ingest discards
thread identity.

---

## Cassandra Stress Commands

Stress testing commands under `cassandra stress`.

### cassandra stress start

Start a stress job on Kubernetes.

```bash
easy-db-lab cassandra stress start [options]
```

**Aliases:** `run`

### cassandra stress stop

Stop and delete stress jobs.

```bash
easy-db-lab cassandra stress stop [options]
```

### cassandra stress status

Check status of stress jobs.

```bash
easy-db-lab cassandra stress status
```

### cassandra stress logs

View logs from stress jobs.

```bash
easy-db-lab cassandra stress logs [options]
```

### cassandra stress list

List available workloads.

```bash
easy-db-lab cassandra stress list
```

### cassandra stress fields

List available field generators.

```bash
easy-db-lab cassandra stress fields
```

### cassandra stress info

Show information about a workload.

```bash
easy-db-lab cassandra stress info <workload>
```

---

## Utility Commands

### exec

Execute commands on remote hosts via `systemd-run`. Tool output is captured by the systemd journal and shipped to Loki through Fluent Bit and the OTel collector, with accurate timestamps for cross-service log correlation.

#### exec run

Run a command on remote hosts (foreground by default).

```bash
# Foreground (blocks until complete, shows output)
easy-db-lab exec run -t cassandra -- ls /mnt/db1

# Background (returns immediately, tool keeps running)
easy-db-lab exec run --bg -t cassandra -- inotifywait -m /mnt/db1/data

# Background with custom name
easy-db-lab exec run --bg --name watch-imports -t cassandra -- inotifywait -m /mnt/db1/data
```

| Option | Description |
|--------|-------------|
| `-t, --type` | Server type: cassandra, stress, control (default: cassandra) |
| `--bg` | Run in background (returns immediately) |
| `--name` | Name for the systemd unit (auto-derived if not provided) |
| `--hosts` | Filter to specific hosts |
| `-p` | Execute in parallel across hosts |

#### exec list

List running background tools on remote hosts.

```bash
easy-db-lab exec list
easy-db-lab exec list -t cassandra
```

#### exec stop

Stop a named background tool.

```bash
easy-db-lab exec stop watch-imports
easy-db-lab exec stop watch-imports -t cassandra
```

### help

Show task-oriented help topics packaged with the tool. These are short guides to common
operations, not a listing of command-line flags.

Run with no argument to list every topic and its description:

```bash
easy-db-lab help
```

Run with a topic name to print that topic's guide. Matching is case-insensitive:

```bash
easy-db-lab help provisioning
```

The seed topics are `provisioning`, `kits`, `stress-testing`, `profiles`, `connecting`, `querying`,
`observability`, `spark`, and `cassandra` (the `cassandra` topic covers database lifecycle, version
selection, and configuration on a running cluster). Topics are packaged markdown files, so `help`
works from a Homebrew install with no source checkout. An unknown topic prints an error that names
the bad topic, lists the valid ones, and exits non-zero.

The standard `-h`/`--help` output points here too: the root usage carries a footer directing you to
`help`, and each subcommand that maps to a topic names the related `help <topic>`.

### ip

Get IP address for a host by alias.

```bash
easy-db-lab ip <alias>
```

### version

Display the easy-db-lab version.

```bash
easy-db-lab version
```

### repl

Start interactive REPL.

```bash
easy-db-lab repl
```

### server

Start the server for Claude Code integration, REST status endpoints, and live metrics.

```bash
easy-db-lab server
```

See [Server](../integrations/server.md) for details.

---

## Logs Commands

### logs query

Read the cluster's logs from Loki.

```bash
easy-db-lab logs query [options]
```

| Option | Description | Default |
|--------|-------------|---------|
| `--source`, `-s` | Log source: `cassandra`, `journald`, `system`, `tool-runner`, `emr` | All sources |
| `--host`, `-H` | Hostname (`db0`, `app0`, `control0`) | All hosts |
| `--unit` | systemd unit | All units |
| `--since` | Time range (`1h`, `30m`, `1d`) | `1h` |
| `--limit`, `-n` | Most lines to return | 100 |
| `--grep`, `-g` | Only lines containing this text | None |
| `--query`, `-q` | Raw LogQL query, sent unchanged | None |

Every option but `--query` is scoped to this cluster; a raw query reads every cluster in the tenant unless it names a `cluster`. A redirect cluster has no local Loki, so the command refuses there. See [Logs (Loki)](../user-guide/loki.md).

Metrics and logs are no longer snapshotted: Mimir and Loki write to S3 themselves, and `down` flushes them. The `metrics backup`, `metrics import`, `metrics ls`, `logs backup`, `logs import` and `logs ls` commands are gone.

## Kubernetes Commands

### k8 apply

Apply observability stack to K8s cluster.

```bash
easy-db-lab k8 apply
```

### platform cni

Show the pod-network datapath. Read-only.

```bash
easy-db-lab platform cni
```

On a Cilium cluster, prints the routing mode, IPAM mode, kube-proxy replacement, masquerade interfaces, native routing CIDR, and the Hubble UI URL, then one block per node with its ENI count, subnet CIDRs, and IPs allocated, used, and available. The values are read from the `cilium-config` ConfigMap and the `CiliumNode` objects on the control node. On a Flannel cluster, prints one line that names Flannel and exits 0. See [Pod Networking (CNI)](../user-guide/networking.md).

---

## Grafana Commands

### grafana update-config

Build and apply the full observability stack to the K8s cluster, including Grafana and its core dashboards. `up` runs this automatically.

The core dashboards are copied as a file tree onto the control node's Grafana data directory (`/mnt/db1/grafana/dashboards`), one subdirectory per Grafana folder. Grafana's file provider re-reads that directory every 10 seconds and files each dashboard into a folder named after its subdirectory. The command reads the tree from the installed build, so run `./gradlew installDist` after editing a dashboard before running it from a source checkout.

```bash
easy-db-lab grafana update-config
```

### grafana install

Upload a single dashboard JSON file to the running Grafana instance through its HTTP API. This is the one-off path for a dashboard that is not part of the core tree; it does not touch the copied tree, and the dashboard is updated in place only if the JSON carries a top-level `uid`.

```bash
easy-db-lab grafana install my-dashboard.json --folder=experiments
```

| Option | Description | Default |
|--------|-------------|---------|
| `--folder` | Grafana folder to install into; created if it does not exist | `General` |

### grafana annotate

Create a Grafana annotation on the running cluster. Use it to drop an A/B config-change marker on the dashboards' timeline; for example, before and after a Cassandra setting change.

A plain global marker (no `--dashboard` and no `--panel` scope) is automatically tagged `easydblab`, in addition to any `--tags` you pass. Every annotation is also mirrored to Loki as it is created, and the core dashboards read their markers from Loki, so a global marker renders on every core dashboard and is still readable from S3 after the cluster is gone. A scoped marker (`--dashboard` or `--panel`) is not auto-tagged; it renders on its target dashboard. If Grafana creates the annotation but the mirror to Loki fails, the command reports the created annotation and its id, then exits non-zero; the next `grafana backup` or `down` mirrors it. The command reaches Grafana over the SOCKS proxy. If the Grafana API is unreachable, the command exits non-zero and names the endpoint.

```bash
easy-db-lab grafana annotate --text "raised concurrent_writes to 128" --tags config
```

| Option | Description | Default |
|--------|-------------|---------|
| `--text` | The annotation body text (required) | - |
| `--tags` | Tags to attach; repeat the flag or comma-separate | none |
| `--time` | Start time: `now`, a relative offset like `-30m`/`-2h`/`-1d`, an ISO-8601 instant, or epoch millis | `now` |
| `--time-end` | Optional end time; produces a region annotation (same formats as `--time`) | none |
| `--dashboard` | Optional dashboard UID to scope the annotation to one dashboard | all dashboards |
| `--panel` | Optional panel id to scope the annotation to one panel | all panels |

### grafana backup

Back up the cluster's Grafana annotations to the observability store in the account bucket.

The annotations are the A/B config-change markers worth keeping after the ephemeral cluster is torn down. The artifact lands at `observability/annotations/<tenant>/<yyyyMMdd-HHmmss>_<name>-<clusterId>.json`, so backups from clusters that share a tenant never overwrite each other. The command reports the resulting S3 URI on success. If no S3 bucket is configured, it fails fast with the standard "run `up` first" message.

Before it writes the file, the command mirrors every annotation to Loki (`source="annotation"`), so the annotations can also be read from Loki's store after the cluster is gone; see [Logs (Loki)](../user-guide/loki.md#annotations). An annotation older than 8760 hours or more than 24 hours ahead is outside the window Loki accepts: it is skipped with a warning naming its id, and it is still in the JSON backup.

The backup captures up to 5000 annotations in one call. If the cluster has 5000 or more, the command fails and backs up nothing, rather than persisting the first 5000 as a complete backup. This makes a truncated backup impossible to mistake for a full one.

```bash
easy-db-lab grafana backup
```

This backup also runs automatically before teardown; see [`down`](#down).

---

## ClickHouse Commands

### clickhouse start

Deploy ClickHouse cluster to K8s.

```bash
easy-db-lab clickhouse start [options]
```

### clickhouse stop

Stop and remove ClickHouse cluster.

```bash
easy-db-lab clickhouse stop
```

### clickhouse status

Check ClickHouse cluster status.

```bash
easy-db-lab clickhouse status
```

---

## Spark Commands

### spark submit

Submit Spark job to EMR cluster.

```bash
easy-db-lab spark submit [options]
```

### spark status

Check status of a Spark job.

```bash
easy-db-lab spark status [options]
```

### spark jobs

List recent Spark jobs on the cluster.

```bash
easy-db-lab spark jobs
```

### spark logs

Query a Spark job's logs from Loki (the most recent job, or the one `--step-id` names).

```bash
easy-db-lab spark logs [options]
```

---

## OpenSearch Commands

### opensearch start

Create an AWS OpenSearch domain.

```bash
easy-db-lab opensearch start [options]
```

### opensearch stop

Delete the OpenSearch domain.

```bash
easy-db-lab opensearch stop
```

### opensearch status

Check OpenSearch domain status.

```bash
easy-db-lab opensearch status
```

---

## AWS Commands

### aws vpcs

List all easy-db-lab VPCs.

```bash
easy-db-lab aws vpcs
```
