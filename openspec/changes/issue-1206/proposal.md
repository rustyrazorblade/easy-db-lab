## Why

The Alloy eBPF profiler has no queue.  Each 15-second collect must reach Pyroscope within that 15 seconds, because Alloy wraps every send of a collect in a deadline of one collect interval and drops what is still unsent when it expires.  So a Pyroscope restart or outage longer than about 15 seconds loses the eBPF profiles of every node: the only profiles of ClickHouse, TiKV, PD and every other non-JVM database.  Issue #991 closed the same gap for metrics, logs, traces, the Pyroscope Java agent and the JFR shipper, and left this one as #1206.  The newest Alloy (v1.22.0) still has the same deadline, so no upgrade closes it.

A second fact limits every profile sender, the Java agent of #991 included: Pyroscope rejects a profile older than `reject_older_than`, 1 hour by default, and a profile is stamped when it is collected.  An outage of 1 hour therefore loses its first profiles even when the sender kept them.

## What Changes

- Add a **profile relay**: a small in-memory store-and-forward proxy that runs as a sidecar in every `pyroscope-ebpf` pod.  Alloy writes to the relay on `127.0.0.1`; the relay accepts each push at once, holds it in a bounded in-memory queue sized for 1 hour of the node's profiles, and forwards it to the real Pyroscope (local or redirected) with the tenant header Alloy sent.  It retries without limit on a connection failure, 5xx, 408, 429, and the 422 bodies Pyroscope answers while it shuts down or starts.  It drops a push Pyroscope rejects with any other 4xx.  It never writes to disk.
- The relay is a new Gradle module, `profile-relay`, built on the JDK alone (no third-party runtime dependency).  Its jar ships in the distribution and reaches the nodes in the `pyroscope-ebpf` ConfigMap, so no node upload, no AMI change and no source checkout is needed, and it works on any Kubernetes cluster.
- `config.alloy`'s `pyroscope.write` targets the relay.  Alloy keeps collecting and symbolizing as it does now.
- Pyroscope's `limits.reject_older_than` is set to 2 hours, so a profile replayed after an outage of up to 1 hour is accepted.  This also makes the 1-hour Java-agent cover of #991 true.
- The relay and the compactor's lease container share one pinned JRE image, `eclipse-temurin:21.0.13_9-jre`; the lease container's unpinned `eclipse-temurin:21-jre` goes.
- Docs and the stale #991 text are corrected: the eBPF profiler's cover becomes 1 hour, held in memory.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `observability-store`: eBPF profiles wait out a Pyroscope outage of up to 1 hour; Pyroscope accepts profiles up to 2 hours old; the eBPF profiler's writes pass through the relay with the tenant; the pinned image list gains the JRE image.
- `telemetry-redirect`: a redirecting cluster deploys the profile relay with the eBPF agent, and the relay forwards to the external Pyroscope.

The unarchived #991 change (`openspec/changes/issue-991/`) is corrected in place: its delta states that eBPF profiles are lost after about 15 seconds, and its Java-agent scenario ignores `reject_older_than`.

## Impact

- New module `profile-relay` (`settings.gradle`, its `build.gradle.kts`, the distribution's `agents/` directory and the Jib image's `/agents`).
- `configuration/pyroscope/PyroscopeManifestBuilder` (relay container, the jar in the ConfigMap, the write URL), `config.alloy`, `config.yaml`.
- `Constants` (relay port, queue size, the shared JRE image), the reserved host ports in `node-tuning/60-easydblab.conf`.
- Tests: relay unit tests, a Docker integration test of Alloy through the relay across a Pyroscope outage, `PyroscopeManifestBuilderTest`, the `pyroscope.yaml` golden manifest, `K8sServiceIntegrationTest`, `NodeTuningReservedPortsTest`.
- Docs: `docs/user-guide/profiling.md`, `docs/user-guide/monitoring.md`, the `observability-stack` skill, `configuration/CLAUDE.md`, the root `CLAUDE.md` module list.
