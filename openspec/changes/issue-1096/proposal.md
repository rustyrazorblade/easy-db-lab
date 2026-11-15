## Why

Part of #1032 (Kubernetes-forward epic), phase A. PR #1084 (#1037) declares node capabilities per environment. A cluster the user brings needs four more, and a declared value can be wrong there: Pod Security admission, GKE Autopilot and policy engines such as Kyverno decide at admission time, per namespace. The architecture (`docs/plans/2026-11-11-kubernetes-forward-architecture.md`, "Capabilities") has code ask for a capability, never for an environment name.

Owner decisions, 2026-11-13 (issue comment on #1096):

- Scope split. This change ships the vocabulary, the recorded set, the `cluster-config` key, the kit variable, the `status` listing, and the probe with its overrides. Calling the probe from `init --kubeconfig` lands with #1082.
- The probe is a server-side dry-run of Pods, not of a DaemonSet.
- The node-access capability is named `NODE_SSH` now and is renamed `NODE_ACCESS` when #1039 lands. `NODE_FILES` (#1080) is out of scope.
- A webhook that refuses dry-run makes the capability absent, with a note naming the webhook.
- Only the effective set is recorded. Overrides use a repeatable `--capability <name>=<bool>`.

## What Changes

- `NodeCapability` gains `PRIVILEGED`, `HOST_NETWORK`, `HOST_PATH` and `NODE_SSH`, each with its absent reason.
  - AWS declares all four.
  - Local Docker declares `PRIVILEGED`, `HOST_NETWORK` and `HOST_PATH`, and not `NODE_SSH`.
- `ClusterState.capabilities` records the effective set. `NodeCapabilities.from(state)` returns it, or the AWS set when a state records none.
- `init --capability <name>=<bool>` (repeatable) overrides any capability in the vocabulary. An unknown name or a value other than `true`/`false` fails `init` and lists the valid names. `init` records the effective set and prints, for each absent capability, which components it disables.
- `CapabilityProbeService` probes a namespace with three server-side dry-run Pods, built on #1089's `KubeApi`. It is not called from `init` until #1082.
- `cluster-config` gains a `capabilities` key: the present capabilities' serial names, comma-joined.
- `EDL_NODE_SSH` is derived from `NODE_SSH in capabilities` instead of the existence of `sshConfig`.
- `status` lists every capability: present, or absent with its reason.
- Docs: `configuration/CLAUDE.md` ("Node Capabilities"), `docs/development/kits.md` (`EDL_NODE_SSH`), the `init` reference page.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `node-capabilities` (added by #1037, not yet archived, so this change adds requirements to it rather than modifying canonical ones): four new capabilities, the recorded set, overrides, the probe, publishing, and the `status` listing.

## Impact

- `configuration/NodeCapability.kt`, `configuration/ClusterState.kt`, `commands/Init.kt`, `commands/Status.kt`, `services/ClusterConfigData.kt`, `services/KitProcessEnvironment.kt`, a new `services/CapabilityProbeService.kt`.
- Merges after #1089. The probe takes its `KubeApi` and `KubernetesClientFactory` as specified on branch `issue-1089` (2cf5dbb1).
- No stage gates on the four new capabilities in this change. #1092 and #1091 add those gates.
