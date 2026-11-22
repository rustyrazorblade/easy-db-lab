## Superseded during review

The temporary-file staging this design describes (the stdin bullet under Interface, R2, and the upload text in `proposal.md`, `tasks.md` and `specs/cluster-tooling/spec.md` here) was replaced before merge.  Input now goes as the command's stdin over the same SSH exec channel (`ChannelRemoteExec` sets it on the channel), its position is the sealed `ToolArg.Input` passed to `runWithInput` rather than a `-` string, and nothing is written on either machine.  The `helm list` release probe also gained `--all`, so a release left pending is found.  The change ports PR #1078.  `openspec/specs/cluster-tooling/spec.md` is the current statement; where this archive and that spec differ, the spec holds.

## Context

`KubectlService` and `HelmService` ran every command through `RemoteOperationsService.executeRemotely` on the control node, after uploading any manifest to a temporary file there, and each caller passed the control host.  `WorkloadStepExecutor` calls them for every typed kit step except configmap, label, exec and the platform-pvs PV objects, which go through Fabric8.  `KitWorkloadProbe` and `KitStatusCommand` call `HelmService.releaseExists`.

## Goals / Non-Goals

Goals: one seam for helm and kubectl, with the SSH form as its only implementation and unchanged AWS behaviour; failures that carry the tool's exit status, stderr and the node it ran on.

Non-goals: a form that runs the tools on the developer's machine, and selecting the form per environment (#1163); node filesystem commands (#1039).

## Decisions

### D1. Only the SSH form ships

The original change also carried a local-process form, bound only by a Koin override in tests.  The owner decided on 2026-11-21 to ship only the SSH form: nothing in the CLI would use the local form, and it has had no real-cluster test.  It returns with #1163, which also chooses the form per environment.

### D2. The `host` parameter is dropped

`KubectlService` and `HelmService` methods no longer take a `host`.  `SshClusterTooling` resolves the control host from cluster state on every call.

- Why: the target is the cluster, not a host.
- Alternative: keep `host` and pass it through (rejected: smaller diff, leaky contract).

### D3. Arguments are quoted by their characters only

`SshClusterTooling` joins an argument made only of shell-safe characters as it is and single-quotes any other one (`shellQuote()`).  There is no per-call list of arguments to quote.

- Why: the characters decide whether the remote shell would split or expand a word; a caller-maintained index set could only drift from them.  Fixed flags and plain values stay byte-identical to the old command strings.

### D4. `createNamespace` applies a fabric8 `Namespace`

`createNamespace` builds a `Namespace` with fabric8, serializes it, and runs `kubectl apply -f -` once.  `kubectl apply` keeps it idempotent.

- Why: one call instead of a dry run plus an apply, and no YAML built from strings.

### D5. platform-pvs steps are out of scope

`platform-pvs` and `platform-pvs-delete` also run `sudo mkdir` / `sudo rm -rf` over SSH on the db nodes.  That is node filesystem work and belongs to #1039.

### Interface

```kotlin
enum class ClusterTool { KUBECTL, HELM }

interface ClusterTooling {
    fun run(tool: ClusterTool, args: List<String>, stdin: String? = null, echo: Boolean = true): String
}

class ClusterToolFailedException(
    val tool: ClusterTool, val args: List<String>, val exitStatus: Int?, val location: String,
    val stderr: String, val stdout: String = "",
) : EasyDBLabException(...)  // the command is redacted; stderr and stdout arrive already tailed and redacted by SSHClient
```

- With stdin, exactly one `-` argument must be present.  The SSH form uploads the input to `/tmp/easydblab-input-<uuid>.yaml`, replaces the `-` argument with that path, and removes the remote file and the local staging copy in `finally`; a failed cleanup is logged and never replaces the command's result.
- `RemoteCommandFailedException` (which gains `exitStatus`) maps to `ClusterToolFailedException`.

## Risks / Trade-offs

- R1. The SSH form's command strings could drift and regress AWS.  Mitigation: `ClusterToolingSshCommandsTest` checks the remote commands each service method runs, and a `/bin/sh` property test checks that every argument reaches the tool as exactly one word.
- R2. A temporary file left behind on the control node.  Mitigation: removed in `finally`, tested for a failed command and a failed upload, and checked on a real cluster.

## Testing

- Unit: `SshClusterToolingTest` (quoting, stdin upload and cleanup, failure mapping, control host re-read) and `ClusterToolingSshCommandsTest` (each service method's remote commands, and kit-variable injection through a real `/bin/sh`), with a mocked `RemoteOperationsService` as CLAUDE.md allows.  Service decisions through a recording `ClusterTooling` fake.
- Real cluster: a 3-node run exercises the typed steps (namespace, helm repo, helm with values, upgrade, uninstall, manifest-url or kustomize, wait, delete-by-selector), a deliberate kubectl failure, and checks that no input file is left on the control node.
