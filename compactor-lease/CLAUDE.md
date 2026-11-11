# compactor-lease Module

This module is the account compactor's lease agent.  It runs in the `lease` container of the `account-compactor` pod, on every cluster's control node.  It makes sure that only one pod per object store runs its compactors.  The user guide is `docs/user-guide/compactor.md`; the pod itself is built in the root module's `configuration/compactor/` (see `configuration/CLAUDE.md`).

## Why it is a separate module

The module has no dependency on the root module.  It ships as a shadow jar, `agents/compactor-lease.jar` in the distribution and `/agents` in the Jib image.  A stock JRE image (`eclipse-temurin:21-jre`) runs it, so the pod works on dev and branch builds and on both CPU architectures.  `CompactorAgentInstaller` uploads the jar to `/mnt/db1/compactor/agent/<sha256>.jar` on the control node; a new jar has a new path, so it rolls the pod.

Keep the dependency direction one way: the root module may use this module, and this module never uses the root module.  The root module uses it in two places:

- `S3ObjectStore` and the in-memory `ObjectStore` delegate their conditional writes to `ConditionalObjects`, so the S3 conditional logic exists once.
- `CompactorLeaseReader` parses the lease with `LeaseRecord` for `observability compactor status`.

## Classes

- `ConditionalObjects` — the conditional-write port: `read`, `createIfAbsent` and `replaceIfVersionMatches`.  It is the only primitive the lease uses.  No operation is retried, because a retry of a write that landed reports a lost race.
- `S3ConditionalObjects` — the port on S3, with `If-None-Match: *` and `If-Match: <ETag>`.  S3 412 and 409, and 404 on a replace, are a lost race; everything else throws.  The SDK's retries are off for both PUTs.
- `LeaseRecord` — the body of `leases/compactor.json`: holder, sequence, TTL and renewal time.  No lease decision reads the renewal time.
- `LeaseTiming` — the timing values.  It cannot be built when the worst-case stop is not less than the TTL.  The values come from `Constants.Compactor.Lease` in the root module, through environment variables.
- `LeaseClock` — one node's monotonic and wall clocks.  Every lease decision uses the monotonic clock only.
- `LeaseLoop` — the lease state machine.  It creates, renews, takes over and gives up the lease, and opens or closes the gate.  A renewal that does not succeed reads the lease back once and keeps the lease when the store holds its own write.  It catches `Exception` only: an `InterruptedException` closes the gate and propagates with the flag set, and `runUntilInterrupted` is the agent's loop.
- `Gate` and `GateFile` — what the agent tells the compactor containers.  `GateFile` writes `valid-until` (epoch seconds, by a write and a rename) and the `ready` marker for the readiness probe, in the emptyDir the pod shares.
- `ScratchWipe` — empties the compactors' scratch directories when the agent takes the lease.
- `LeaseAgentConfig` — the agent's configuration, read from the pod's environment.
- `LeaseAgentMain` — the jar's entry point.  It uses the node's instance role through IMDS and logs every lease transition to stdout.

The gate script that reads `valid-until` is not in this module.  It is `compactor-gate.sh` in the root module's resources, and its tests are in `src/test/shell/`.

## Tests

- The unit tests run with `./gradlew :compactor-lease:test`.  `LeaseLoopTest` drives the state machine on a fake clock against `InMemoryConditionalObjects`.
- The test fixtures hold `InMemoryConditionalObjects` and `ConditionalObjectsContract`.  The contract runs against the in-memory adapter here, against the root module's `InMemoryObjectStore` in `src/test/`, and against `S3ObjectStore` on LocalStack in `src/integrationTest/`.  Every adapter must pass the same contract.
- `CompactorPodIntegrationTest` in the root module's `src/integrationTest/` runs the real jar and the real gate in containers.
