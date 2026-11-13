## Context

`NodeCapability` (`configuration/NodeCapability.kt:16`) has `SYSTEMD_JOURNAL`, `CLOUDWATCH` and `S3`. `NodeCapabilities.from(state)` returns AWS unconditionally (`NodeCapability.kt:51`), because #1040 has not recorded the environment in state. Stages declare `requires` (`ObservabilityStackService.kt:112`) and a skipped stage emits `Event.Grafana.StageSkipped(stage, capability)` (`Event.kt:2508`), whose text comes from `absentReason`. `EDL_NODE_SSH` is set from the existence of `sshConfig` (`KitProcessEnvironment.kt:53`). `cluster-config` is built by `ClusterConfigData.of()` (`ClusterConfigData.kt:27`).

There is no `init --kubeconfig` yet (#1082). #1089 (spec on branch `issue-1089`, 2cf5dbb1, PR #1107) adds `KubeApi(kubeconfig, context)` and `KubernetesClientFactory.createClient(api: KubeApi)`.

## Goals / Non-Goals

**Goals:** the four capabilities, a recorded effective set, overrides at `init`, a probe that tells what a namespace admits, publishing the set to `cluster-config` and to kit processes, and a `status` listing.

**Non-Goals:**
- Calling the probe from `init --kubeconfig` (#1082).
- Gating stages on the new capabilities (#1092, #1091).
- `NODE_ACCESS` (#1039) and `NODE_FILES` (#1080).
- Kit `requires:` (#1055).
- Probing on AWS or local.

## Decisions

### D1. Probe with server-side dry-run Pods

`CapabilityProbeService.probe(api: KubeApi, namespace: String): ProbeResult` creates the namespace when it is missing, because a dry-run in a missing namespace returns 404. Version 1 requires cluster-admin (decision K10). The service then submits four Pods with `dryRun=All`:

| Pod | Differs from the baseline Pod by | Decides |
| --- | --- | --- |
| baseline | nothing | whether the probe can attribute a result at all |
| privileged | `securityContext.privileged: true` (and `allowPrivilegeEscalation: true`) | `PRIVILEGED` |
| host network | `hostNetwork: true` | `HOST_NETWORK` |
| host path | one `hostPath` volume, `/var/log`, read-only | `HOST_PATH` |

The baseline Pod meets Pod Security "restricted": `runAsNonRoot`, seccomp `RuntimeDefault`, all capabilities dropped, no privilege escalation, the pause image. Each probe Pod changes only the one field it tests, so a rejection is attributable to that field. The privileged Pod necessarily also lifts the escalation flag, because the API server's Pod validation rejects `privileged: true` with `allowPrivilegeEscalation: false` even under `dryRun=All`. When the baseline Pod itself is rejected, the probe fails, naming the reason: the namespace or the credentials admit no Pod at all, which is not a capability fact. The Pod builders are Fabric8 builders, not YAML strings.

A rejection with HTTP 403 or 422, or a 400 whose message says a webhook does not support dry run, makes the capability absent. The result carries a note with the API message. For the dry-run refusal, the note names the webhook (decision 3). Any other error fails the probe.

Every failure, including reading or creating the namespace and waiting for a created namespace's default ServiceAccount (`Constants.Capabilities.NAMESPACE_READY_TIMEOUT_SECONDS`), is an `IllegalStateException` naming the namespace and the API server's message; a 403 there also says the probe needs cluster-admin. An interrupt during the wait is rethrown with the thread's flag set.

`NODE_SSH` is never probed. A probed set has it absent. The other capabilities (`SYSTEMD_JOURNAL`, `CLOUDWATCH`, `S3`) are not probed either: #1082 decides their defaults for that environment.

*Alternatives:*
- A dry-run DaemonSet. Rejected: Pod Security enforce applies to Pods, and a controller gets only a warning, so the dry-run would pass and report a false "present".
- `SelfSubjectAccessReview`. Rejected: it checks RBAC only and cannot see admission.
- Reading the namespace's `pod-security.kubernetes.io/*` labels. Rejected: it misses the cluster-wide default and policy engines, and the target EKS cluster has no labels.
- A real probe Pod. Rejected: side effects and a scheduling wait for no extra information.

### D2. Record the effective set in state

`ClusterState.capabilities: Set<NodeCapability>? = null`, serialized by serial name, like `cni`. `NodeCapabilities.from(state)` returns `NodeCapabilities(state.capabilities)` when it is set, and `AWS` otherwise. A state written before this change still reads as AWS. When #1040 lands, its environment supplies the declared set that `init` starts from. Nothing else changes.

Only the effective set is recorded (decision 4): no provenance and no probe notes in state. The notes are printed at `init`.

### D3. Overrides

`init --capability <name>=<bool>` is repeatable. `<name>` is a capability's serial name (`privileged`, `host-network`, `host-path`, `node-ssh`, `systemd-journal`, `cloudwatch`, `s3`). `<bool>` is `true` or `false`. Parsing is a pure function, `CapabilityOverrides.parse(List<String>)`, which fails on an unknown name, a bad value or a name given twice with different values, listing the valid names. `init` computes `effective = declared (or probed) + overrides` and records it. Today the declared set is AWS, since `init` creates AWS clusters only.

### D4. `init` reports what an absent capability disables

`init` prints one block for every absent capability: the capability, its `absentReason`, and the components that need it. The component list comes from the stage declarations themselves. `ObservabilityStackService` exposes the stage label and `requires` pairs without building manifests, so `init` and `up` share one source. A capability that no component needs yet prints "no component needs it yet". This is plain informational output (`println`), not an event.

### D5. Publish

- `cluster-config`: the key `capabilities`, the present capabilities' serial names in enum order, comma-joined. The order is stable so that `ConfigHashAnnotator` does not roll a workload whose set did not change.
- Kit processes: `KitProcessEnvironment` sets `EDL_NODE_SSH` to `NODE_SSH in NodeCapabilities.from(state)`. That is one source of truth, and on AWS it is still `true`. No other variable is added; kit `requires:` is #1055.

### D6. `status`

`status` prints a "Capabilities" section: each capability in enum order, "present", or "absent: <absentReason>". Read-only output, `println`.

## Risks / Trade-offs

- **A policy engine that rejects only in real runs, ignoring dry-run.** The probe would report the capability present while real Pods are rejected. Mitigation: the override, and #1102 explains the real rejection.
- **A webhook that mutates rather than rejects** (for example, one that strips `privileged`). Dry-run would report the capability present. Accepted for version 1; the override is the escape hatch.
- **The probe creates the namespace.** That is a real side effect at `init`. It is accepted under K10, and #1094 labels the namespace as easy-db-lab's.
- **`EDL_NODE_SSH` changes source.** On AWS it stays `true`. A workspace with `sshConfig` and an override `node-ssh=false` now reports `false`, which is the intent.

## Dependencies

- **#1089** (`KubeApi`, `KubernetesClientFactory.createClient(api)`). This change is based on `origin/local-k8s-edbl` and merges after #1089 (PR #1107). Until then, the probe's tests build the client from the TestContainer's kubeconfig through the same interface.
- **#1082** wires `CapabilityProbeService` into `init --kubeconfig`. That is not a blocker for this change.
