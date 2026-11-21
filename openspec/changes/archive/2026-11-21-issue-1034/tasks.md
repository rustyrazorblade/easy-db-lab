## 1. Exit status on remote failures

- [x] 1.1 Write an `SSHClient` test: a remote command that exits 2 raises `RemoteCommandFailedException` with `exitStatus` 2.
- [x] 1.2 Add `exitStatus` to `RemoteCommandFailedException` and fill it in `SSHClient.remoteCommandFailure`.

## 2. The seam and the SSH form

- [x] 2.1 Add `services/ClusterTooling.kt`: `ClusterTool`, `ClusterTooling`, `ClusterToolFailedException`, with KDoc.
- [x] 2.2 Write `SshClusterToolingTest`: the `KUBECONFIG=/etc/rancher/k3s/k3s.yaml` prefix, quoting by character (with a `/bin/sh` round-trip property), the `-` argument replaced by the uploaded path with stdin, both files removed when the command or the upload fails, a failed cleanup never replacing the result, and exit-status mapping.
- [x] 2.3 Implement `SshClusterTooling`, moving `withKubeconfig` and shell quoting out of the services.  It resolves the control host from cluster state.

## 3. The services

- [x] 3.1 Write service tests with a recording `ClusterTooling` fake for the decisions each service makes (values on stdin, `releaseExists`, delete-by-selector lookup then delete, the fabric8 namespace).
- [x] 3.2 Change `KubectlService` and `HelmService` to run through `ClusterTooling` and drop the `host` parameter.
- [x] 3.3 Update `WorkloadStepExecutor`, `KitWorkloadProbe`, `KitStatusCommand` and `KitRunnerCommand`.  Keep existing tests green.
- [x] 3.4 Bind `single<ClusterTooling> { SshClusterTooling(get(), get(), Path.of(System.getProperty("java.io.tmpdir"))) }` in `ServicesModule`.

## 4. Docs and rules

- [x] 4.1 Replace the `RemoteOperationsService` rule in `CLAUDE.md`: helm and kubectl go through `ClusterTooling`, never `ProcessBuilder`.  Point the mocking rule for remote CLI tools at `SshClusterTooling`.
- [x] 4.2 Update `commands/CLAUDE.md`, `services/CLAUDE.md` and `docs/development/kits.md`.
- [x] 4.3 Run `./gradlew ktlintCheck detekt test integrationTest` (JDK 21) in a subagent.
