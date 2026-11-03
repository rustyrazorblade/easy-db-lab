## Context

`KubectlService` (`services/KubectlService.kt:76-95`) and `HelmService` run every command through `RemoteOperationsService.executeRemotely` on the control node, after uploading any manifest to a temporary file there.  `WorkloadStepExecutor` (`:121-210`) calls them for every typed kit step except configmap, label, exec and the platform-pvs PV objects, which go through Fabric8.  `KitWorkloadProbe:108` and `KitStatusCommand:82` call `HelmService.releaseExists`.  The spike for epic #1032 replaced `RemoteOperationsService` with a `docker exec` shim to run the postgres kit end to end on a local cluster (FINDINGS B7, B8).

## Goals / Non-Goals

Goals: one seam for helm and kubectl with an SSH form (unchanged AWS behaviour) and a local-process form; failures that carry the tool's exit status and stderr; no temporary manifest files in the local form.

Non-goals: selecting the form per environment (#1040); node filesystem commands (#1039); the SOCKS proxy.

## Decisions

All decisions below were approved by the owner on 2026-10-31.

### D1. Locally, helm and kubectl run on the host

`LocalClusterTooling` runs helm and kubectl as host processes through `KitProcessEnvironment` and the workspace wrappers, the same environment kit shell steps use.

- Why: shell steps already need host helm and kubectl, so kits like postgres and memcached already need more than Docker.  One environment for every kit step, and no new image.
- Alternatives: `docker exec` into the K3s container (rejected: `rancher/k3s` ships kubectl but no helm, and epic decision Q4 rules out a custom node image); a pinned tools image run with `docker run --network container:<k3s>` (rejected: adds an image and duplicates the wrappers, for a "Docker only" guarantee shell steps already break).

### D2. The `host` parameter is dropped

`KubectlService` and `HelmService` methods no longer take a `host`.  `SshClusterTooling` resolves the control host from cluster state.

- Why: the target is the cluster, not a host; the local form would otherwise ignore a host it was handed.
- Alternative: keep `host` and pass it through (rejected: smaller diff, leaky contract).

### D3. The local form is selected by a Koin override until #1040

`servicesModule` binds `single<ClusterTooling> { SshClusterTooling(get(), get(), Path.of(System.getProperty("java.io.tmpdir"))) }`.  Tests and harnesses rebind it.  The third argument is the directory for the SSH form's local staging copy of stdin; only the SSH form takes it.

- Why: no user-facing switch that #1040 would have to remove.
- Alternative: a hidden `EDL_CLUSTER_TOOLING=local` variable (rejected: a throwaway switch).

### D4. platform-pvs steps are out of scope

`platform-pvs` and `platform-pvs-delete` also run `sudo mkdir` / `sudo rm -rf` over SSH on the db nodes (`WorkloadStepExecutor.kt:229,337`).  That is node filesystem work and belongs to #1039, which now carries the acceptance criterion for those two steps.

- Alternative: pull `NodeExec` into this change (rejected: doubles the scope).

### D5. The integration test writes the `direct` key itself

Until #1040 adds `direct` network mode, a workspace wrapper with no recorded tunnel exits 1 with the `socks start` message.  The local-form integration test writes the `direct` key into the workspace env file itself.  It does not borrow `EDL_TAILSCALE_ACTIVE` (forbidden by epic decision Q2).

### Interface

```kotlin
enum class ClusterTool { KUBECTL, HELM }

interface ClusterTooling {
    /**
     * Runs [tool] with [args], feeding [stdin] when given; returns stdout.  [shellQuoted] holds the
     * indexes into [args] that the SSH form single-quotes; the local form ignores it.
     */
    fun run(
        tool: ClusterTool,
        args: List<String>,
        stdin: String? = null,
        echo: Boolean = true,
        shellQuoted: Set<Int> = emptySet(),
    ): String
}

class ClusterToolFailedException(
    val tool: ClusterTool, val args: List<String>, val exitStatus: Int, val stderr: String,
) : EasyDBLabException(...)
```

- SSH form: builds `KUBECONFIG=${REMOTE_KUBECONFIG} <tool> <args>`, single-quoting only the arguments the service marked in `shellQuoted` and joining the rest raw, as today.  With stdin, uploads it to `/tmp/easydblab-input-<uuid>.yaml`, replaces the `-` argument with that path, and runs `rm -f path` in `finally`.  Exactly one `-` argument must be present when stdin is given.  Maps `RemoteCommandFailedException` (which gains `exitStatus`) to `ClusterToolFailedException`.
- Local form: `ProcessBuilder` with the kit process environment applied; stdin piped; stdout and stderr captured separately; stdout echoed with `println` when `echo` is set.  A non-zero exit throws with the last `Constants.ClusterTooling.FAILURE_TAIL_LINES` lines of stderr.  Arguments are passed as a list, never through a shell.
- Services: `applyContent` becomes `run(KUBECTL, ["apply", "-f", "-"], yaml)`; `createNamespace` runs the dry-run, then applies its output through stdin.

## Risks / Trade-offs

- R1. The SSH form's command strings could drift and regress AWS.  Mitigation: unit tests pin today's command strings.
- R2. `helm repo add` in the host form writes the user's `~/.config/helm`.  Mitigation: point `HELM_CONFIG_HOME`, `HELM_CACHE_HOME` and `HELM_DATA_HOME` at the workspace.
- R3. `helm --values -` support must hold for the pinned helm version.  Mitigation: the integration test runs a helm step with values.
- R4. The integration test needs network access (helm-repo) and helm and kubectl on PATH.  If missing, it fails with a clear message; it never skips.
- R5. Fabric8 steps locally depend on #1040's direct network mode.  Out of scope; the test writes the key itself (D5).
- R6. A temporary file left behind on the control node or the host.  Mitigation: SSH form removes in `finally`; local form never writes one.

## Testing

- Unit: `SshClusterTooling` command strings, the `-` argument replaced by the uploaded path, `rm` running even when the command fails, the injected temporary directory empty after a successful and a failed run, exit-status mapping (mocks `RemoteOperationsService` with `argumentCaptor`, as CLAUDE.md allows).  Service argument building through a recording `ClusterTooling` fake that asserts on the arguments the service built.
- Integration (`LocalClusterToolingIntegrationTest`): its own K3s container, a `@TempDir` workspace holding its kubeconfig, `RemoteOperationsService` bound to a fake that fails any call.  Runs a fixture kit through `WorkloadStepExecutor` covering helm-repo, helm with values, helm-uninstall with keep-while, namespace, manifest, manifest-url, kustomize, wait, delete, delete-by-selector.  Asserts a bad kind fails with exit status 1 and stderr naming the resource type, and the SOCKS properties still null.  The local form's "no temporary file" is structural (`LocalClusterTooling` pipes stdin and creates no file), not a runtime assertion; see `ac-coverage.md`.
