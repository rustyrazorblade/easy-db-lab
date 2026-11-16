## Context

Today the account compactor is an ECS Fargate task with five containers in one network namespace: a busybox config writer, Mimir's compactor, Loki's compactor, and Tempo's backend-scheduler and backend-worker.  There is no compactor program of our own; the task runs the Grafana images with the cluster's own config files plus compactor flags.  The task role is the only principal allowed to delete under `mimir/`, `loki/` and `tempo/`.  Pyroscope compaction already runs in each cluster.

The Grafana images are distroless.  Mimir runs `/bin/mimir` as root; Loki runs `/usr/bin/loki` and Tempo runs `/tempo` as user 10001.  There is no shell in any of them.

## Goals / Non-Goals

**Goals:**
- Run the same four compactor processes, with the same flags, in a pod on every cluster's control node.
- Make exactly one of them active per object store, with a lease built only on conditional writes.
- A holder that cannot renew stops work before any standby can take over.
- Remove the Fargate path completely.

**Non-Goals:**
- A cloud-specific serverless runner.
- Compaction while no cluster is up.
- GCS and Azure conditional-write adapters.
- Retention or deletion of any kind.

## Decisions

### D1. The lease agent is a `:compactor-lease` subproject, shipped as a jar

A small Gradle subproject with no dependency on the root module holds the conditional-write port (`ConditionalObjects`: `read`, `createIfAbsent`, `replaceIfVersionMatches`), `S3ConditionalObjects`, `InMemoryConditionalObjects` (in test fixtures), `LeaseLoop`, `LeaseRecord`, `GateFile` and `LeaseAgentMain`.  It is shadowed into `agents/compactor-lease.jar`, the same way the OTel agent ships.  The Jib image also gets it under `/agents`.  `ObservabilityStackService.deploy()` uploads it to `/mnt/db1/compactor/agent/<sha256>.jar`, skipping the upload when the file exists, so `grafana update-config` gets it too.  A stock multi-arch JRE image runs it.

`ConditionalObjects` is the only conditional-write port; `ObjectStore` has no conditional methods.  The lease agent uses the port directly, and so does the CLI's `status` command, through `CompactorLeaseReader`, which reads the lease with `S3ConditionalObjects` and parses it with `LeaseRecord`.  The root module's integration tests run the subproject's contract against `S3ConditionalObjects` on LocalStack.

### D2. A deadline file and a busybox gate enforce "stop work"

The pod `account-compactor` runs:

- an init container (`busybox:1.37.0-musl`, which is statically linked) that copies `/bin/busybox` into an emptyDir at `/gate/bin`;
- the `lease` container, which writes `/lease/valid-until` (epoch seconds) to a shared emptyDir with a write and a rename while it holds the lease, and removes it when it loses the lease;
- the four compactor containers, each started as `/gate/bin/busybox sh /config/compactor-gate.sh -- <binary> <args>`.

The gate script calls every applet explicitly (`/gate/bin/busybox date`, `/gate/bin/busybox sleep`), because the distroless images have no applets on `PATH`.  It:

1. waits until `valid-until` is later than now;
2. starts the binary as a child;
3. checks every second; when `valid-until` is not later than now, or the file is gone, it sends SIGTERM, waits 20 seconds, sends SIGKILL, and goes back to step 1;
4. starts the binary again if it exits while the lease is valid;
5. traps SIGTERM, passes it to the child, waits for it, and exits.  The gate is PID 1, and the kernel ignores SIGTERM for PID 1 without a handler.

The gate fences on its own: if the agent dies or hangs, `valid-until` stops moving and the gate stops the binary.  The pod's `terminationGracePeriodSeconds` is 60, longer than the gate poll plus the grace.

The gated containers have no readiness probe, so a standby is Ready and `up` does not fail on the second cluster.  The lease container's readiness probe passes once it has read the lease, so an S3 or credential problem shows up during `up` on every cluster.  The agent logs every lease transition to stdout, and the cluster's log collection ships it to Loki.

### D3. The instance role may delete under the compacted roots

The compactor containers use the node's instance role through IMDS (the hop limit is already 2).  The roots `mimir/`, `loki/` and `tempo/` leave the instance role's delete deny and the bucket policy's deny for the instance role.  The deny stays for `grafana/` and `reports/`, and the bucket-policy deny stays for the EMR roles.  The compactor task role, its execution role and its policy are deleted.

The cluster's own backends still have no deletion path: Mimir has no compactor target, Loki's compaction interval is so long it never runs, and Tempo has compaction disabled for every tenant.  Tempo's retention runs inside its backend worker and skips only compaction-disabled tenants, so the cluster `tempo.yaml` also gets `block_retention: 876000h` as a second guard.

### D4. `status` stays; `start` and `stop` go

There is no account service to start or stop.  `observability compactor status` becomes a read-only print of the lease (holder, sequence, renewal time, age).  It works outside a workspace and reads with the bucket's region (`BucketRegion`).

### D5. The pod uses the pod network

The compactor configs reuse the cluster backends' ports (Mimir 9009/9097/7947, Loki 3100/9098, Tempo 3200/9096).  On the host network they would collide with the cluster's own backends.  A pod network namespace gives the same private `127.0.0.1` the Fargate task had, so `tempo-backend.yaml` and the memberlist settings work unchanged.  IMDS from the pod network is verified in QA.

### D6. No graceful release

A pod that stops does not release the lease.  A standby takes over after the TTL.  The acceptance criteria require only takeover after expiry, and an EC2 termination does not reliably deliver SIGTERM.

### Lease format and timing

The lease is `leases/compactor.json`, outside every backend root (under `mimir/`, Mimir would read it as a tenant).  Its body:

```json
{"holder":"<cluster>-<id>/<pod>","sequence":42,"ttlSeconds":120,"renewedAt":"2026-11-07T18:00:00Z"}
```

The version token is the S3 ETag.  `sequence` goes up on every write, so a renewal always gets a new ETag.  `renewedAt` is for `status` only.

Each process uses only its own monotonic clock, as in client-go leader election:

- The holder's valid-until is the send time of its last successful renewal plus the renew deadline.
- A standby takes over only after it has seen the same ETag for at least the TTL on its own clock, and then replaces the object with If-Match on that ETag.
- The standby's first read of a version comes after that write landed, which comes after the holder sent it.  The holder stops by `renewDeadline + gatePoll + gateGrace` after that send.  So `renewDeadline + gatePoll + gateGrace < TTL` is the safety margin.

| Setting | Value |
|---|---|
| TTL | 120 s |
| Renew interval | 15 s |
| Renew deadline | 60 s |
| Standby poll | 15 s |
| Gate poll | 1 s |
| Gate grace | 20 s |
| Pod termination grace | 60 s |

The worst-case stop is 81 seconds, under the 120-second TTL.  Failover takes about 2 to 2.5 minutes.  A `LeaseTiming` value class checks the margin when it is built.  The values live in `Constants.Compactor.Lease` and reach the agent and the gate as environment variables.

The holder identity is the cluster label plus the pod name (Downward API).  A restarted pod does not take back its own lease; it waits for the TTL, which is safe while a terminating pod's processes may still run.

### Conditional writes and failures

- S3 412 and 409 map to `AlreadyExists` or `VersionMismatch`.  A 404 on replace maps to `VersionMismatch`.  Anything else throws.
- Conditional writes are not retried.  A retry of a write that landed gets 412, which only makes the holder think it lost.  A write that landed but answered otherwise is caught by the read-back below instead.
- A renewal that does not succeed (it throws, or returns `VersionMismatch`) is followed by one read of the lease, in the same round.  If the lease holds exactly the record the holder just tried to write (same holder, sequence and renewal time), the write landed and only its answer was lost: a timeout after S3 committed it, or a 409 for a conditional write in flight.  The holder adopts the version it read and keeps the lease.  The adopted write renews the gate only if the gate is open and the renew deadline has not passed; otherwise the gate stays closed until the next renewal that succeeds.  The read-back is not retried.
- Otherwise, a renewal that returns `VersionMismatch`, or finds the lease missing, makes the holder revoke the gate file at once and become a standby.  A renewal that throws keeps the old version and is tried again at the next renew interval; the gate closes once the renew deadline passes.
- The read-back runs in the same round, not at the start of the next one.  `storeCallTimeout` is a per-call bound (the SDK's API call timeout), and nothing in the margin depends on how many calls a holder makes in a round: the gate's window is counted from the send time of the write that landed, so a slower round only renews later, never extends a window.  A holder's round with a read-back takes at most two `storeCallTimeout`s, 39 seconds with the default timing, under the 60-second renew deadline.
- An `InterruptedException` is never a failed round.  It closes the gate and propagates with the interrupt flag set; the agent's loop then returns and the process exits.  Only `Exception` is caught as a failed round; a JVM `Error` propagates.

### Scratch space

The compactors' scratch directories are host paths on the data mount, `/mnt/db1/compactor/{mimir,loki,tempo}`, as Fargate had 100 GiB of ephemeral storage.  `CompactorAgentInstaller` creates them and gives Loki's and Tempo's to user 10001, on every cluster, redirect clusters included.  The agent wipes them when it acquires the lease, before it writes `valid-until`, so a cluster that regains the lease hours later does not replay stale Tempo scheduler jobs.

### Rollout

The compactor is one more stage in `ObservabilityStackService.buildStages()`, outside the branch that only runs without a telemetry redirect.  `ConfigHashAnnotator` restarts the pod when its ConfigMap changes; the jar's SHA-256 path in the pod template restarts it when the jar changes.  The environment comes from `cluster-config`, with `AWS_REGION` set to the bucket's region, and `TENANT` and `CLUSTER_NAME` set to `compactor`, as today.  No container has a CPU or memory limit.

## Alternatives Considered

### D1: how the lease agent reaches the node
- **Run the CLI's published image with a hidden subcommand.**  Rejected.  Dev and branch builds have no matching image; the image is built for amd64 only, so an arm64 control node breaks; the image is about 300 MB; and a missing image makes `up` fail on ImagePullBackOff.
- **A curated classpath from the root module** (the root jar plus a subset of its runtime classpath).  Rejected.  A missing class shows up only at run time as `NoClassDefFoundError`, so it needs a dedicated test just to stay correct.

### D2: how "stop work" is enforced
- **The agent scales a separate compactor Deployment between 0 and 1 through the Kubernetes API.**  Rejected.  It needs RBAC and fabric8 in the agent; the compactors keep running unfenced if the agent crashes; and every `up` re-apply would fight the agent over `replicas`.
- **The agent starts the Go binaries itself, after copying them out through image volumes.**  Rejected for now.  It depends on the ImageVolume feature in the pinned K3s version, which is not verified.

### D3: delete permission
- **Keep a dedicated compactor role and have the pod assume it.**  Rejected.  The Go S3 clients do not chain AssumeRole from IMDS, so the agent would have to refresh a credentials file; and the instance role must be trusted to assume the role, so the protection is mostly ceremony.

### D4: CLI commands
- **Keep `start` and `stop` as per-cluster scale controls.**  Rejected.  It adds a way to stop compaction that nobody asked for.

### D5: network mode
- **Host network with a reserved port block in `Constants`, overriding every server, gRPC and memberlist port by flag.**  Rejected unless IMDS fails from the pod network in QA.  It adds many flags and a port block to keep free.

### D6: release on shutdown
- **Mark the lease released with an If-Match write when the pod stops, for faster failover.**  Rejected.  The acceptance criteria need only takeover after expiry, and EC2 termination does not reliably send SIGTERM.

## Risks / Trade-offs

- **Fencing is timing-based.**  The Go processes' own S3 writes carry no fencing token, so exclusivity rests on the timing margin and on similar clock rates.  A brief overlap of two compactors produces duplicate merged blocks, not data loss: each compactor removes only sources whose data is in its own merged output (Mimir uses deletion marks, Loki uploads first and uses unique output names, Tempo marks inputs compacted).
- **The deny on the compacted roots is gone.**  Any process with the instance role can now delete under `mimir/`, `loki/` and `tempo/`.  The cluster's own backends have no deletion path, and Tempo gets the second retention guard.
- **A kill in the middle of a compaction leaves partial Mimir blocks.**  Readers ignore them and nothing deletes them, so they are garbage, not loss.
- **LocalStack 3.0 has no conditional writes.**  The S3 contract test needs a newer LocalStack image.  If none works without an auth token, the S3 half of the contract runs against real S3 as a `requires-aws` test.
- **The live Fargate service keeps running after this change.**  Stop it with the previous build's `observability compactor stop` before QA.
- **A standby's broken compactor stays hidden until it acquires the lease.**  The lease container's readiness catches S3 and credential problems on every cluster; a config problem in a compactor shows up in its logs after takeover.
- **Region.**  The compactors and the agent use the bucket's region; the cluster's own backends keep their existing region assumption.
- **Loki starts compacting one full compaction interval (10 minutes) after takeover.**  QA judges takeover by the lease and Mimir or Tempo activity first, and Loki after that.

## Migration Plan

1. Before the first `up` with this build, stop the Fargate service with the previous build's `observability compactor stop`.
2. Run `bin/set-policies` to install the reduced policy set.
3. `up` deploys the in-cluster compactor; `down --all` later removes the old compactor VPC.

## Open Questions

None.
