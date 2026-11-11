## ADDED Requirements

### Requirement: The object store offers conditional writes

The `ConditionalObjects` port in the `compactor-lease` module SHALL be the only conditional-write port.  The lease agent and `CompactorLeaseReader` SHALL use it directly; the `ObjectStore` interface SHALL NOT offer conditional operations.  The port SHALL offer three operations, each with a version token:

- `read` SHALL return the object's content and its version, or nothing when the object does not exist.
- `createIfAbsent` SHALL write the object only when no object exists at that key.  It SHALL return `Created` with the new version, or `AlreadyExists`.
- `replaceIfVersionMatches` SHALL write the object only when its current version equals the expected version.  It SHALL return `Replaced` with the new version, or `VersionMismatch`.  A missing object SHALL count as `VersionMismatch`.

The S3 adapter, `S3ConditionalObjects`, SHALL implement them with conditional PUT: `If-None-Match: *` for create and `If-Match: <ETag>` for replace, and the version token SHALL be the ETag.  S3 status 412 (precondition failed) and 409 (conditional request conflict) SHALL map to `AlreadyExists` or `VersionMismatch`, and a 404 with error code `NoSuchKey` on a replace SHALL map to `VersionMismatch`.  Any other failure, a 404 for a missing bucket included, SHALL throw.  The conditional operations SHALL NOT be retried, because a retry of a write that landed returns 412 and only makes the caller think it lost.  An in-memory adapter, `InMemoryConditionalObjects`, SHALL implement the port, and one contract test suite SHALL run against both adapters.

#### Scenario: Two writers create the same key
- **WHEN** two writers call `createIfAbsent` for the same key
- **THEN** exactly one gets `Created`, and the other gets `AlreadyExists`
- **AND** this holds on the in-memory adapter and on the S3 adapter

#### Scenario: A stale version cannot replace an object
- **WHEN** `replaceIfVersionMatches` runs with a version that is not the object's current version
- **THEN** it returns `VersionMismatch`
- **AND** the object's content and version do not change

#### Scenario: A matching version replaces an object
- **WHEN** `replaceIfVersionMatches` runs with the object's current version
- **THEN** it returns `Replaced` with a new version, and `read` returns the new content with that version

### Requirement: Every cluster runs a standby compactor on its control node

`up` and `grafana update-config` SHALL deploy one `account-compactor` Deployment on the control node of every cluster, clusters that redirect their telemetry included.  The Deployment SHALL be built with fabric8 and SHALL run 1 replica with the `Recreate` strategy.  Its pod SHALL use the pod network, not the host network, so the compactor ports never collide with the cluster's own backends.  It SHALL hold:

- a `lease` container that runs the lease agent jar from the distribution with a stock JRE image;
- a Mimir container with `-target=compactor` and `-compactor.cleanup-interval=1m`, a Loki container with `-target=compactor`, and a Tempo backend-scheduler and a Tempo backend-worker container, each wrapped by the gate;
- no Pyroscope container, because Pyroscope compaction stays in each cluster.

The compactors SHALL compact every tenant in the account bucket, and Loki SHALL compact every table, today's included.  The compactor containers SHALL use the node's instance role for S3 and the account bucket's region.  No container SHALL have a CPU or memory limit, and no memory limiter SHALL be added to any compactor or OTel component.  The scratch directories SHALL be host paths on the data mount (`/mnt/db1/compactor/...`), created and owned by the user each container runs as, in every mode.  The lease agent jar SHALL be uploaded to the control node at a path named by its SHA-256, so a new jar changes the pod template and rolls the pod.  `up` SHALL NOT create any ECS service, task definition, compactor VPC or compactor IAM role.

#### Scenario: up deploys the compactor on every cluster
- **WHEN** `up` runs on a cluster, with or without a telemetry redirect
- **THEN** the control node runs one `account-compactor` pod with the lease container and the four gated compactor containers
- **AND** no container runs Pyroscope

#### Scenario: up creates no Fargate resources
- **WHEN** `up` runs on AWS
- **THEN** no ECS service, task definition, compactor VPC or compactor IAM role is created

#### Scenario: The compactor has no limits
- **WHEN** the compactor Deployment is built
- **THEN** no container has a CPU or memory limit, and no memory limiter is configured

#### Scenario: A new agent jar rolls the pod
- **WHEN** the lease agent jar changes and `up` or `grafana update-config` runs
- **THEN** the new jar is uploaded to a new SHA-256 path and the pod is replaced

### Requirement: Exactly one compactor per store holds the lease

The lease SHALL be the object `leases/compactor.json` in the account bucket, outside every backend's root.  Its body SHALL hold the holder, a sequence number, the TTL in seconds, and the renewal time.  The sequence SHALL go up on every write, so every renewal gets a new version.  The lease SHALL be written only with `createIfAbsent` and `replaceIfVersionMatches`; a plain PUT or a DELETE SHALL never be used on it.  Each process SHALL use only its own monotonic clock; no decision SHALL compare two nodes' clocks or read the renewal time.

- A compactor with no lease object SHALL create it.
- The holder SHALL renew every renew interval with `replaceIfVersionMatches` on its last version.
- A standby SHALL poll the lease and SHALL take it over only after it has seen the same version for at least the TTL on its own clock, with `replaceIfVersionMatches` on that version.
- A pod that restarts SHALL NOT take back its own earlier lease; it waits like any standby.
- A holder SHALL wipe its scratch directories when it acquires the lease, before any compactor starts.

The timing SHALL be held in `Constants`: TTL 120 seconds, renew interval 15 seconds, renew deadline 60 seconds, standby poll 15 seconds, gate poll 1 second, gate grace 20 seconds.  The timing SHALL satisfy `renewDeadline + gatePoll + gateGrace < TTL`, and building a timing that breaks it SHALL fail.

#### Scenario: Two clusters share one store
- **WHEN** two clusters run their compactors against one account bucket
- **THEN** exactly one compactor holds the lease and compacts, and the other stays idle

#### Scenario: A standby takes over after expiry
- **WHEN** the holder stops renewing, tested with the in-memory object store and a fake clock
- **THEN** the standby does not take the lease before the TTL has passed on its own clock
- **AND** the standby acquires the lease once the TTL has passed

#### Scenario: Timing that breaks the safety margin is refused
- **WHEN** a lease timing is built whose renew deadline plus gate poll plus gate grace is not less than the TTL
- **THEN** building it fails

### Requirement: A holder that cannot renew stops work

The lease agent SHALL write the time until which its lease is valid to a file shared with the compactor containers, by writing a temporary file and renaming it.  The valid-until time SHALL be the send time of the last successful renewal plus the renew deadline.  When a renewal throws or returns `VersionMismatch`, the agent SHALL read the lease back once before it decides.  When the lease holds exactly the record the agent tried to write, the agent SHALL keep the lease at the version it read; that write SHALL NOT reopen a gate that is closed or past the renew deadline.  Otherwise, when a renewal returns `VersionMismatch`, or the lease is missing, the agent SHALL remove the file and become a standby.  An interrupt SHALL remove the file and end the agent.

Each compactor container SHALL run its binary under a gate script, run by a static busybox (`busybox:1.37.0-musl`) copied in by an init container, with every applet called explicitly.  The gate SHALL:

- start the binary only while the valid-until time is in the future;
- check the file every gate poll interval, and when the time has passed or the file is gone, send SIGTERM to the binary, wait the gate grace, send SIGKILL, and go back to waiting;
- start the binary again if it exits while the lease is still valid;
- trap SIGTERM and pass it to the binary, so a pod shutdown stops the binary cleanly.

The gate SHALL stop the binary on its own when the agent dies, because the valid-until time stops moving.  The pod's termination grace period SHALL be longer than the gate poll plus the gate grace.  The gated containers SHALL have no readiness probe, so a standby is Ready; the lease container's readiness SHALL show that it can read the lease.

#### Scenario: A holder that loses the lease stops compacting
- **WHEN** the holder's renewal fails and the valid-until time passes
- **THEN** every compactor binary in its pod is stopped within the gate poll plus the gate grace

#### Scenario: A renewal that landed but answered otherwise keeps the lease
- **WHEN** the holder's renewal times out, or answers 409, after the store committed it
- **THEN** the holder reads the lease back, finds its own write, and keeps the lease and its gate

#### Scenario: A dead agent fences its own compactors
- **WHEN** the lease container stops while it holds the lease
- **THEN** the gates stop every compactor binary after the valid-until time passes

#### Scenario: A standby runs no compactor
- **WHEN** a compactor pod does not hold the lease
- **THEN** no Mimir, Loki or Tempo compactor process runs in it, and the pod is Ready

### Requirement: Compactor status reads the lease

The CLI SHALL provide `observability compactor status`.  It SHALL work outside a cluster workspace.  It SHALL print the lease's holder, sequence, renewal time and age, read with the account bucket's region, or that no lease exists.  When the lease object exists but does not hold a valid lease, it SHALL say that the lease object cannot be read, show its content, and say that deleting `leases/compactor.json` lets a pod create a fresh lease.  It SHALL change nothing.  The CLI SHALL NOT provide `observability compactor start` or `observability compactor stop`.

#### Scenario: Status shows the holder
- **WHEN** a user runs `observability compactor status` and a lease exists
- **THEN** the output shows the holder, the sequence, the renewal time and the age of the lease
- **AND** nothing is started, stopped or changed

#### Scenario: Status with no lease
- **WHEN** a user runs `observability compactor status` and no lease exists
- **THEN** the output says that no compactor holds the lease

#### Scenario: Status with a lease it cannot read
- **WHEN** a user runs `observability compactor status` and `leases/compactor.json` does not hold a valid lease
- **THEN** the output says that the lease object cannot be read, shows its content, and says that deleting `leases/compactor.json` lets a pod create a fresh lease
- **AND** nothing is changed

## MODIFIED Requirements

### Requirement: The compactor compacts and never deletes data by retention

The compactor SHALL compact, and SHALL run with retention and every other deletion path off.  It MAY remove source objects only after it has written a merged copy that holds their data.

- Mimir's compactor SHALL run with `-compactor.blocks-retention-period=0` and `-compactor.partial-block-deletion-delay=0`.
- Loki's compactor SHALL run with retention disabled and deletion mode `disabled`.
- Tempo's backend-worker SHALL run with `block_retention` of `876000h`, `max_bytes_per_trace` of 0, and `empty_tenant_deletion_enabled` false, and with no per-tenant retention override.

These settings SHALL be locked by tests on the compactor Deployment that the manifest builder produces.

#### Scenario: Retention is off in every tool
- **WHEN** the compactor Deployment and its configuration files are built
- **THEN** Mimir's blocks retention period and partial block deletion delay are 0, Loki's retention is disabled with deletion mode `disabled`, and Tempo's block retention is `876000h` with no per-tenant override
- **AND** Tempo's `max_bytes_per_trace` is 0 and `empty_tenant_deletion_enabled` is false

#### Scenario: Compaction keeps every sample, log line and trace
- **WHEN** the compactor has compacted a tenant's metrics, logs and traces
- **THEN** every sample, log line and trace written before is still returned by queries
- **AND** no object is removed unless its data is in the merged output

## REMOVED Requirements

### Requirement: One compactor service runs per account

**Reason**: The ECS Fargate service is removed.  The compactor runs in every cluster, and the lease picks the one active compactor.

**Migration**: Stop the existing Fargate service with the previous build's `observability compactor stop` before the first `up` with this build.  The `easy-db-lab-compactor` VPC is torn down by `down --all` like any other tagged VPC.

### Requirement: Only the compactor's task role can delete observability objects

**Reason**: The compactor task role and execution role are removed.  The in-cluster compactors use the node's instance role, and the lease makes sure only one of them compacts.

**Migration**: None.  The `observability-store` capability specifies the remaining deletion guards.

### Requirement: The compactor is controlled by hand

**Reason**: There is no account service to start or stop.  `status` stays, as the requirement "Compactor status reads the lease" specifies.

**Migration**: None.

### Requirement: The compactor's network stays out of cluster teardown

**Reason**: The compactor no longer has its own VPC.

**Migration**: None.  `down --all` removes the old `easy-db-lab-compactor` VPC with the other tagged VPCs.
