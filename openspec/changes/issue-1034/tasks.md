## 1. Exit status on remote failures

- [ ] 1.1 Write an `SSHClient` test: a remote command that exits 2 raises `RemoteCommandFailedException` with `exitStatus` 2.
- [ ] 1.2 Add `exitStatus` to `RemoteCommandFailedException` and fill it in `SSHClient.remoteCommandFailure`.

## 2. The seam and the SSH form

- [ ] 2.1 Add `services/ClusterTooling.kt`: `ClusterTool`, `ClusterTooling`, `ClusterToolFailedException`, with KDoc.  Add `Constants.ClusterTooling.FAILURE_TAIL_LINES`.
- [ ] 2.2 Write `SshClusterToolingTest`: the `KUBECONFIG=/etc/rancher/k3s/k3s.yaml` prefix, argument quoting, `< path` with stdin, `rm -f path` run even when the command fails, and exit-status mapping (mock `RemoteOperationsService`, verify with `argumentCaptor`).
- [ ] 2.3 Implement `SshClusterTooling`, moving `withKubeconfig` and shell quoting out of the services.  It resolves the control host from cluster state.

## 3. The services

- [ ] 3.1 Write service tests with a recording `ClusterTooling` fake that assert the argument lists each method builds today, including `createNamespace` without a shell pipe.
- [ ] 3.2 Change `KubectlService` and `HelmService` to run through `ClusterTooling` and drop the `host` parameter.
- [ ] 3.3 Update `WorkloadStepExecutor`, `KitWorkloadProbe` and `KitStatusCommand`.  Keep existing tests green.
- [ ] 3.4 Bind `single<ClusterTooling> { SshClusterTooling(get(), get()) }` in `ServicesModule`.

## 4. The local form

- [ ] 4.1 Write `LocalClusterToolingIntegrationTest` (own K3s container, `@TempDir` workspace with its kubeconfig and the `direct` env-file key written by the test, a `RemoteOperationsService` fake that fails any call).  Run a fixture kit through `WorkloadStepExecutor` covering helm-repo, helm with values, helm-uninstall with keep-while, namespace, manifest, manifest-url, kustomize, wait, delete and delete-by-selector.  Assert: a bad kind fails with exit status 1 and stderr naming the resource type; no `easydblab-*` files in `java.io.tmpdir`; helm's repository config is under the workspace; `socksProxyHost` and `socksProxyPort` stay unset.  Missing helm or kubectl fails clearly, never skips.
- [ ] 4.2 Implement `LocalClusterTooling` with `ProcessBuilder` and `KitProcessEnvironment`, stdin piped, stdout echoed with `println`, helm homes under the workspace.

## 5. Docs and rules

- [ ] 5.1 Replace the `RemoteOperationsService` rule in `CLAUDE.md` with: "`RemoteOperationsService` runs node commands on AWS hosts (filesystem, systemd, node configuration) and AWS-only cluster tooling (cilium).  helm and kubectl for kits and the services go through `ClusterTooling`: its SSH form runs them on the control node with `KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}`; its local form runs them as a local process in the kit process environment.  Never call `ProcessBuilder` for helm or kubectl outside `LocalClusterTooling`."  Point the mocking rule for remote CLI tools at `SshClusterTooling`.
- [ ] 5.2 Update `commands/CLAUDE.md`, `services/CLAUDE.md` and `docs/development/kits.md` (local runs need helm and kubectl on the host, as shell steps already do).
- [ ] 5.3 Run `./gradlew ktlintFormat detekt test integrationTest` (JDK 21) in a subagent.
