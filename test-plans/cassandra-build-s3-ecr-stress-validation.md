# Lab Plan: `cassandra build` S3 install + ECR stress image

## Objective

Prove the two seams on branch `cassandra-build-s3` that unit tests cannot reach, because both only
execute on a real cluster node:

1. **S3 install path** (commit `27d0b39c`) — a Cassandra built locally by `cassandra build` and
   published to the account bucket is discovered by listing S3, and is fetched by each node over
   `s3://` using its instance profile through the new `cached_fetch` branch.
2. **ECR pull secret** (commit `bd94fa8a`) — `cassandra stress --image <ECR image>` pulls from the
   account's ECR, which was impossible before `EcrPullSecretService` was wired into the stress path.

Success is specific: the install log shows `s3 fetch:` (not `cache miss` + curl), the build lands on
**every** db node with `java: 21` recorded, and the stress pod reaches `Running` rather than
`ImagePullBackOff`. Anything else is a failure of the code under test.

Secondary, and the reason for the custom stress image: observe whether the rate-limiter optimizer's
rebuilt control loop converges against a 20 ms read/write latency target at 30k ops/s.

## Cluster Name

cassandra-build-s3-ecr

## Datacenters

single

## Environment

3 db nodes + 1 stress node, `i4i.xlarge`, us-west-2. Local NVMe, so no EBS configuration.
`AWS_PROFILE=sandbox-admin` for any direct AWS CLI calls.

**Artifact 1 — Cassandra build under test** (already published; verified present in S3):

| | |
|---|---|
| Build name | `6.0-alpha3-cursor-bti-20260905-b8c4c07-jdk21` |
| Location | `s3://easy-db-lab-61e80d2c-634a-49d3-8333-3d2758000d9d/cassandra-builds/<build name>/` |
| base.version | 6.0-alpha3 |
| Git sha | `b8c4c0766b` on `21460-cursor-bti`, **dirty tree (14 modified files)** |
| Build JDK | 21 |
| Size | 70 MiB |

**Artifact 2 — stress image under test** (already published; verified present in ECR):

```
102382809497.dkr.ecr.us-west-2.amazonaws.com/rustyrazorblade/cassandra-easy-stress:optimizer-latency-signal
```

From `rustyrazorblade/cassandra-easy-stress` commit `970d58c`, *"Fix the rate limiter optimizer's
control loop"*.

## Steps

### 1. Provision

3 db + 1 stress node. Nothing else is installed; the AMI's baked Cassandra versions are irrelevant
here beyond giving `cassandra list` something to contrast against.

```bash
$EDB init --db.count 3 --app.count 1 --db.instance-type i4i.xlarge --app.instance-type i4i.xlarge --up cassandra-build-s3-ecr
```

### 2. The published build is discovered by listing S3

This is the catalog-discovery check. The build was never declared in any local yaml — if it appears,
it can only have come from listing the bucket.

```bash
$EDB cassandra list
```

**Pass:** `6.0-alpha3-cursor-bti-20260905-b8c4c07-jdk21` is listed, marked `(build, not installed)`.
**Fail:** absent, or listed without the marker (which would mean it was mistaken for an installed
version).

### 3. Install it by name, and prove the fetch came from S3

No `--url` and no `--java`: every parameter comes from the manifest in S3. Passing either flag would
defeat the point of the step.

```bash
$EDB cassandra install 6.0-alpha3-cursor-bti-20260905-b8c4c07-jdk21
```

**Pass:** the per-host output contains `s3 fetch: s3://easy-db-lab-.../cassandra-builds/...tar.gz`,
and the command reports success on all three hosts.
**Fail:** a `cache miss:` line followed by a curl download (the `s3://` branch was not taken), any
`403`/`AccessDenied` (the instance profile was not used), or a host-level failure.

Capture the install output verbatim into the journal — the `s3 fetch:` line is the single most
important piece of evidence in this plan.

### 4. The bits and the declaration landed on every node

Checked on **all three** db nodes, not just the first — the install runs per host in parallel and a
partial success is exactly the failure worth catching.

```bash
CLUSTER_DIR=$(dirname "$EDB")
BUILD=6.0-alpha3-cursor-bti-20260905-b8c4c07-jdk21
for h in db0 db1 db2; do
  echo "--- $h ---"
  ssh -F "$CLUSTER_DIR/sshConfig" $h \
    "ls -d /usr/local/cassandra/$BUILD && grep -A3 'cursor-bti' /etc/cassandra_versions.yaml"
done
```

**Pass:** the directory exists on all three, and each node's entry carries `java: "21"`.
**Fail:** a missing directory on any node, or a `java` value other than 21 — the latter means the
manifest's JDK did not propagate, and `cassandra use` would then select the wrong JDK.

### 5. Activate the version

```bash
$EDB cassandra use 6.0-alpha3-cursor-bti-20260905-b8c4c07-jdk21

CLUSTER_DIR=$(dirname "$EDB")
for h in db0 db1 db2; do
  echo -n "$h: "; ssh -F "$CLUSTER_DIR/sshConfig" $h "java -version 2>&1 | head -1"
done
```

**Pass:** `use` succeeds on all hosts and the selected JDK reports 21.
**Fail:** `use` errors on a missing `java`/`python` field, or a JDK other than 21 is selected.

Steps 2–5 are what prove the install mechanism. They are complete on their own.

### 6. Start Cassandra

```bash
$EDB cassandra start
$EDB cassandra nt status
```

**Pass:** all three nodes UP/NORMAL.

**Read a failure here carefully.** This build came off an active development branch with a dirty
tree. A node that will not start is at least as likely to be a defect in `21460-cursor-bti` as a
problem with the install path, and steps 3–5 have already proven the install path independently.
Record a startup failure as a finding against the *branch*, capture
`/var/log/cassandra/system.log`, and do not retroactively mark steps 3–5 failed. If Cassandra will
not start, stop here and report — step 7 cannot run without a live cluster.

### 7. Stress with the ECR image — the pull-secret check

First a **2-minute smoke run with the identical image and flags**. It costs 2 minutes and catches
an argument-parse failure, a driver/protocol mismatch against a 6.0 trunk build, a schema failure,
or missing metrics — any of which would otherwise waste the full hour:

```bash
IMG=102382809497.dkr.ecr.us-west-2.amazonaws.com/rustyrazorblade/cassandra-easy-stress:optimizer-latency-signal

$EDB cassandra stress start --image $IMG -- RandomPartitionAccess \
  -d 2m -p 100k --workload.rows=10 --populate 250k --threads 4 \
  --rate 30k --readrate 0.2 --cl LOCAL_QUORUM \
  --maxrlat 20 --maxwlat 20
```

Only if the smoke run reaches `Running`, creates its schema, and reports traffic, start the real run:

```bash
$EDB cassandra stress start --image $IMG -- RandomPartitionAccess \
  -d 60m -p 100k --workload.rows=10 --populate 250k --threads 4 \
  --rate 30k --readrate 0.2 --cl LOCAL_QUORUM \
  --maxrlat 20 --maxwlat 20
```

Immediately confirm the secret exists and is actually referenced, before waiting on the run:

```bash
$EDB cassandra stress status

CLUSTER_DIR=$(dirname "$EDB")
ssh -F "$CLUSTER_DIR/sshConfig" control0 \
  "sudo k3s kubectl get secret ecr-pull-secret -n default"
ssh -F "$CLUSTER_DIR/sshConfig" control0 \
  "sudo k3s kubectl get pod -n default -l app.kubernetes.io/name=cassandra-easy-stress \
     -o jsonpath='{.items[*].spec.imagePullSecrets[*].name}'"
```

**Pass:** the pod reaches `Running`, `ecr-pull-secret` exists in `default`, and the pod's
`imagePullSecrets` names it.
**Fail:** `ImagePullBackOff` or `ErrImagePull` — a hard failure of commit `bd94fa8a`. Capture
`kubectl describe pod` for the pull error verbatim; a 401 means the secret was wrong, an absent
secret means it was never created.

**Every flag above is load-bearing. Do not simplify this command.**

| Flag | Why |
|---|---|
| `--threads 4` | Default is 1. One generator thread cannot sustain 30k ops/s, so utilization stays below `MIN_UTILIZATION = 0.90` and the optimizer never takes its increase path — a client ceiling that is indistinguishable from a broken control loop. |
| `-p 100k --workload.rows=10` | Shrinks the key space from 100M rows to 1M, so it is covered in about a minute. |
| `--populate 250k` | Per thread, so 1M rows total — the whole space. Without it, reads are mostly bloom-filter misses and read p99 climbs monotonically for the entire hour. |
| `--readrate 0.2` | `RandomPartitionAccess` inherits `getDefaultReadRate() = .01`. At the default, `--maxrlat` would be steering on 1% of operations. |
| `--cl LOCAL_QUORUM` | Default `LOCAL_ONE` acks as soon as one replica responds, so the optimizer would happily converge on a rate at which the other two are dropping mutations and accumulating hints. |
| `--rate 30k` | One shared `RateLimiter`, so this is the **global** total, not per-thread. |

**Why populate matters more than it looks:** with both `--maxrlat` and `--maxwlat` set,
`determineCriticalLatency()` steers on whichever of read/write is nearer its target. Against an
uncovered key space it starts write-driven and flips to read-driven partway through the hour. That
flip renders as oscillation in the graphs and is easily misread as a defect in the control loop
under test.

### 8. Observe the rate-limiter optimizer

The optimizer is only constructed when a latency target is set (`Run.kt:476`), so `--maxrlat` /
`--maxwlat` above are what put the code under test on the execution path.

Over the 60-minute run, record from Grafana and `cassandra stress logs`:

- whether the achieved rate converges, or oscillates without settling
- how achieved read/write p99 track the 20 ms targets
- whether the optimizer backs off when latency exceeds target, and recovers when it drops

Render the relevant dashboards to `docs/images/` at roughly 15-minute intervals so the convergence
behaviour over the full hour is visible in the report, not just its endpoint.

### 9. Tear down

```bash
$EDB down --auto-approve
```

## Notes

- **Steps 2–5 and step 7 are independent verdicts.** The first group validates commit `27d0b39c`,
  step 7 validates `bd94fa8a`. Either can pass while the other fails; report them separately.
- **The branch under test is probably not exercised by this run.** `21460-cursor-bti` is BTI /
  cursor-compaction work, but BTI needs `storage_compatibility_mode: NONE` and
  `sstable: selected_format: bti`, and the generated `cassandra.patch.yaml` sets neither — so this
  runs stock 6.0-alpha3 BIG format. That is fine for validating the install seam, which is the
  point of steps 2-5, but do not read the stress numbers as saying anything about the cursor work.
- **Heap and GC are whatever the tarball ships.** `CassandraBuildCatalog` sets `jvmOptions = null`
  for S3 builds and `patch-config` only merges `cassandra.yaml`, so the JVM options come entirely
  from the build's own `conf/jvm*-server.options`. At a 20 ms p99 target on 4 vCPU, GC pauses are a
  large fraction of what the optimizer is measuring — record the resolved flags before the run.
- **20 ms is well past the knee** — healthy p99 here is 1-3 ms, so the loop spends the hour hunting
  around saturation rather than converging cleanly. Deliberate, since saturation is where the fix
  matters, but a 10 ms target would show convergence more legibly if this run is ambiguous.
- **`:latest` in the ECR repo currently points at this branch build**, because the jib block in
  `build.gradle.kts` hardcodes `tags = setOf("latest")`. Pin the explicit tag in every command; do
  not rely on `:latest` meaning anything stable there.
- The ECR authorization token expires after 12 hours. `EcrPullSecretService` rewrites the secret on
  every submission, so a fresh run always re-mints it — but a pod restarting late in a much longer
  soak could still hit an expired credential. Not a risk within this 60-minute run.
- Total expected cluster time is roughly 2 hours: ~15 min provisioning, ~15 min steps 2-6, 60 min
  stress, ~10 min teardown.
