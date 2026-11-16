# Cassandra 6.0-rustyrazorblade — runtime install + 4h stress

## Cluster Name

cassandra-876-cursor

## Datacenters

single

## Steps

1. Provision — 3 db + 1 stress, `i4i.xlarge`
2. `use` before `install` fails helpfully
3. Install on a single host (`--hosts`)
4. `cassandra list` distinguishes installed from declared
5. Install across the whole cluster
6. Re-run is a safe no-op
7. Failure is loud and per-host
8. Activate, set up the cursor-compaction A/B, verify Java 21
9. 4h `RandomPartitionAccess` stress run at `--rate 100k`


Validates issue #876 / PR #878 (`cassandra install <version>`) against a real AWS cluster,
then reuses the same cluster to stress the custom perf patches in the 6.0-rustyrazorblade
build.

This plan does double duty:

1. **Steps 2–8** — walk every acceptance criterion on #876 that needs real hardware. Cheap,
   runs in minutes.
2. **Step 9** — a 4h `RandomPartitionAccess` run at `--rate 100k` against the freshly
   installed build.

## Artifact under test

| | |
|---|---|
| Source repo | `rustyrazorblade/cassandra-builds` |
| Build run | https://github.com/rustyrazorblade/cassandra-builds/actions/runs/33138617467 |
| Release tag | `cassandra-6.0-rustyrazorblade` |
| Version | 6.0-alpha3 (SHA `273046beeb32`) |
| Build JDK | Temurin 21.0.12 |
| Tarball | `apache-cassandra-6.0-alpha3-273046beeb32-bin.tar.gz` (74 MB) |

Tarball URL (public, unauthenticated — verified HTTP 200):

```
https://github.com/rustyrazorblade/cassandra-builds/releases/download/cassandra-6.0-rustyrazorblade/apache-cassandra-6.0-alpha3-273046beeb32-bin.tar.gz
```

Installed as version name **`6.0-rustyrazorblade`** → `/usr/local/cassandra/6.0-rustyrazorblade`.

No `cassandra_versions.yaml` edit and no AMI rebake: install parameters come entirely from
CLI flags, which is the one-off path `CassandraInstall` documents.

---

## Step 1 — Provision

3 Cassandra nodes (the minimum that exercises `--hosts` targeting and the parallel per-host
error reporting this branch rewrote) + 1 stress node.

```bash
$EDB init -c 3 -s 1 -i i4i.xlarge --up
```

**Expect:** 3 db nodes + 1 stress node up, K3s healthy.

**Pre-flight — verify the AMI carries the node-side half of #878.** `cassandra install` does
*not* upload its worker script; it invokes `install-cassandra-version` by bare name on the
node's `PATH`, so an AMI baked before this branch fails every step from 2 on. `use-cassandra`
must also carry the guard that refuses a missing version rather than dangling the symlink.

```bash
CLUSTER_DIR=$(dirname "$EDB")
for h in db0 db1 db2; do
  echo "== $h"
  ssh -F "$CLUSTER_DIR/sshConfig" $h \
    "ls -l /usr/local/bin/install-cassandra-version /usr/local/bin/use-cassandra"
done
```

**Fail if:** `install-cassandra-version` is absent on any node — stop and rebake
(`easy-db-lab build-image`, which builds *both* the base and cassandra AMIs), then re-provision
with `--ami <new cassandra ami>`. Do not hand-copy the scripts onto the nodes; that bypasses the
delivery path this plan exists to validate.

---

## Step 2 — `use` before `install` fails helpfully

AC: *`cassandra use <version>` for a version that isn't installed fails with a clear message
directing the operator to run `cassandra install` first — never a generic error.*

```bash
$EDB cassandra use 6.0-rustyrazorblade
```

**Expect:** non-zero exit. Message names the version and points at `cassandra install`.
**Fail if:** a generic stack trace, a silent success, or a symlink swap to a missing directory.

---

## Step 3 — Install on a single host

AC: *`--hosts` installs only on the targeted subset; untargeted nodes are unaffected.*

```bash
$EDB cassandra install 6.0-rustyrazorblade \
  --url https://github.com/rustyrazorblade/cassandra-builds/releases/download/cassandra-6.0-rustyrazorblade/apache-cassandra-6.0-alpha3-273046beeb32-bin.tar.gz \
  --java 21 \
  --hosts db0
```

**Expect:** success reported for `db0` only. `/usr/local/cassandra/6.0-rustyrazorblade`
exists on `db0` and **not** on `db1`/`db2`. `/etc/cassandra_versions.yaml`
on `db0` gained the entry with `java: 21`; the other two are unchanged.

**Also check:** no credential material written into `/etc/cassandra_versions.yaml` (this branch
explicitly stopped persisting credentials there).

---

## Step 4 — `cassandra list` distinguishes installed from declared

AC: *`cassandra list` shows lazily-declared-but-not-yet-installed versions, distinguishable
from versions actually installed on the node.*

```bash
$EDB cassandra list
```

**Expect:** `6.0-rustyrazorblade` shown as installed on `db0`, and visibly *not*
installed on the other two.

---

## Step 5 — Install across the whole cluster

AC: *tarball `url:` install lands at `/usr/local/cassandra/<version>` on targeted nodes.*

```bash
$EDB cassandra install 6.0-rustyrazorblade \
  --url https://github.com/rustyrazorblade/cassandra-builds/releases/download/cassandra-6.0-rustyrazorblade/apache-cassandra-6.0-alpha3-273046beeb32-bin.tar.gz \
  --java 21
```

**Expect:** `db1`/`db2` install; `db0` reports **already present** rather
than re-downloading 74 MB. All 3 nodes now have the version on disk.

---

## Step 6 — Re-run is a safe no-op

AC: *re-running for an already-installed version does not error and does not re-download or
re-build — logs "already present."*

Re-run the exact command from step 5.

**Expect:** all 3 hosts report already-present. No network transfer, no extraction.
**Fail if:** any node re-downloads, or a false `DeclarationMismatch` fires (the last commit on
this branch specifically fixed that for HEAD-tracking re-installs).

---

## Step 7 — Failure is loud and per-host

AC: *install failure (unreachable/404 tarball) fails loudly with a clear per-host error
identifying the version and the reason — no silent partial state, no fallback.*

```bash
$EDB cassandra install 6.0-bogus-does-not-exist \
  --url https://github.com/rustyrazorblade/cassandra-builds/releases/download/cassandra-6.0-rustyrazorblade/no-such-file-bin.tar.gz \
  --java 21
```

**Expect:** non-zero exit. **Every** failing host reported, not just the first — this branch
rewrote `withHosts` specifically so parallel failures all surface. Error names the version and
the reason (404). No partial directory left behind at `/usr/local/cassandra/6.0-bogus-*` —
the install script now cleans its working directory on every exit path.

---

## Step 8 — Activate, set up the cursor-compaction A/B, and verify Java 21

`cursor_compaction_enabled` defaults to **`true`** — it is not in the shipped
`conf/cassandra.yaml`, and `Config.java:720` initialises it from
`CassandraRelevantProperties.CURSOR_COMPACTION_ENABLED`, whose default is `"true"`. So the
A/B is created by turning it **off** on the control nodes, not on by explicitly setting it.

| Node | `cursor_compaction_enabled` | Role |
|---|---|---|
| `db0` | default (`true`) | treatment — cursor compaction ON |
| `db1` | `false` | control |
| `db2` | `false` | control |

`use` first (so `update-config` resolves `current` to the new version), then patch, then start.

```bash
$EDB cassandra use 6.0-rustyrazorblade
```

> **A patch file is a complete config, not a delta.** `update-config` rebuilds
> `conf/cassandra.yaml` from pristine `conf.orig` on every apply, so a file containing only
> `cursor_compaction_enabled` strips seeds, cluster name and data directories, and the node dies
> at startup with `Found no candidates during initialization ... [/127.0.0.1:7000]`.
> `cassandra use` above already applied `cassandra.patch.yaml` to all three nodes, so `db0` is
> done — only the control arm needs a second, complete file.

```bash
CLUSTER_DIR=$(dirname "$EDB")
derive-host-patch.sh "$CLUSTER_DIR/cassandra.patch.yaml" \
                     "$CLUSTER_DIR/cursor-off.patch.yaml" \
                     cursor_compaction_enabled=false

$EDB cassandra update-config cursor-off.patch.yaml --hosts db1,db2
```

**Verify the written config on all 3 nodes BEFORE starting.** A node that starts with a
stripped config takes the whole cluster down with it, so check first rather than debugging after:

```bash
CLUSTER_DIR=$(dirname "$EDB")
for h in db0 db1 db2; do
  echo "== $h"
  ssh -F "$CLUSTER_DIR/sshConfig" $h \
    "grep -E 'seeds:|cluster_name:|cursor_compaction' \
     /usr/local/cassandra/current/conf/cassandra.yaml"
done
```

**Expect:** all 3 show `seeds:` and `cluster_name:`; `db1`/`db2` additionally show
`cursor_compaction_enabled: false`; `db0` shows no `cursor_compaction` key at all.
**Fail if:** any node is missing `seeds:` — do not start, re-apply the correct superset patch.

```bash
$EDB cassandra start
```

**Expect:** all 3 nodes start on the custom build. A startup failure here means the config is
wrong, not the key — stop rather than continue with a broken arm.

Verify, on each node:

- `nodetool version` reports 6.0-alpha3
- the running Cassandra process is under **Java 21** (not the AMI default)
- `nodetool status` shows 3 nodes `UN`

> `$EDB cassandra nodetool status` rejects passthrough args ("Unmatched arguments from index 1").
> Go through ssh instead:
> `ssh -F "$(dirname "$EDB")/sshConfig" db0 "/usr/local/cassandra/current/bin/nodetool status"`

**Fail if:** the JVM is any version other than 21 — the tarball was built on Temurin 21.0.12,
so a runtime mismatch is a real defect in how `--java` is recorded and consumed.

---

## Step 9 — 4h stress run

The perf half of this plan. Custom perf patches are in this build; this is the load that
exercises them.

```bash
$EDB cassandra stress start RandomPartitionAccess -d 4h --rate 100k --maxrlat 10 --maxwlat 10
```

Args after the subcommand pass through verbatim to cassandra-easy-stress.

**Monitor:** Grafana on the control node (`cassandra-overview`, `system-overview`), plus
`$EDB cassandra stress status` / `stress logs`.

**Record for the report:**

- achieved throughput vs the requested 100k
- read/write latency distributions against the `--maxrlat 10` / `--maxwlat 10` ceilings
- GC behaviour and CPU saturation across the 4h window
- any compaction backlog build-up

**The cursor-compaction A/B — break every compaction metric out per node**, `db0`
(ON) against `db1`/`db2` (OFF), since all three take the same load:

- compaction throughput and pending-task depth
- time spent in compaction, and compaction-related CPU
- read latency as a function of SSTable count per node
- any divergence in disk I/O between the arms

Caveat to state in the report rather than correct for: the arms are not evenly sized (1 vs 2),
and per-node load is only as even as the token distribution makes it.

**Known failure mode to report, not work around:** if `--rate 100k` exceeds what the cluster
can absorb, cassandra-easy-stress aborts queue-full (same mode #875 documented for the sysbench
kit). Report it as a result — do not silently lower the rate.

---

## Teardown

**Do not tear down automatically.** The 4h run is the point of the cluster; leave it up for
inspection afterwards and tear down only on explicit instruction:

```bash
$EDB down --auto-approve
```
