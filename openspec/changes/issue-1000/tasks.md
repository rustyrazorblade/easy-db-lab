## 1. PackagedExecutable and the SsmProxyWrapper migration

- [x] 1.1 Write failing tests for `PackagedExecutable` (root package, beside `ShellQuoting.kt`): writes content and sets it executable; leaves a file with matching content untouched; replaces changed content; stages to a unique temp file in the target directory and renames it into place; fails when `setExecutable` returns false.
- [x] 1.2 Implement `PackagedExecutable`.
- [x] 1.3 Move `providers/ssm/SsmProxyWrapper.kt` onto `PackagedExecutable`, removing its fixed `.tmp` file name. Run the existing SSM wrapper tests (including `SsmProxyWrapperScriptTest`) and confirm they pass.

## 2. Proxy env file and atomic proxy state

- [x] 2.1 Write failing tests for the proxy env file writer: writes only `EDL_TAILSCALE_ACTIVE` and `EDL_SOCKS_PORT`; updating one key keeps the other; removing the port keeps the Tailscale flag; the write is a temp file plus rename; the file sources cleanly in `/bin/sh`.
- [x] 2.2 Implement the writer (one class owns `.socks5-proxy.env`), register it in Koin, and add its file name and keys to `Constants`.
- [x] 2.3 Write a failing test that `.socks5-proxy-state` is written by temp file plus rename, then make `ProcessSocksProxyService` write it atomically.
- [x] 2.4 `ProcessSocksProxyService` writes `EDL_SOCKS_PORT` only after the tunnel is verified, on start and on reuse. Test both paths and the stale-PID restart on a new port.

## 3. Wrapper script and its shell tests

- [x] 3.1 Write the unit-tier JUnit test (`src/test`) that copies the packaged wrapper under the six names into a temp workspace `bin/` with a marker, puts stub binaries later on `PATH`, and runs them through real `/bin/sh`. Cases, all failing first:
  - each tool's proxy variables, upper and lower case;
  - hostile inherited `NO_PROXY`/`no_proxy`/`http_proxy`/`ALL_PROXY` are replaced;
  - Tailscale leaves the environment byte-for-byte unchanged;
  - a port change between two calls is picked up;
  - no port recorded exits 1 with a message naming the workspace and `easy-db-lab start-socks`;
  - no real binary exits 127 with a message on stderr;
  - two workspaces' `bin/` on `PATH` cause no recursion;
  - a symlinked workspace finds its env file;
  - arguments with spaces, stdin, and a non-zero exit code pass through;
  - indirect calls through `env`, `xargs`, `sh -c`, and a nested script go through the wrapper (no `timeout`, which macOS lacks);
  - an unwrapped `aws` stub run by the same step sees no proxy variables;
  - inherited `EDL_TAILSCALE_ACTIVE`/`EDL_SOCKS_PORT` are ignored.
- [x] 3.2 Write `src/main/resources/com/rustyrazorblade/easydblab/configuration/tool-wrapper.sh` (POSIX `#!/bin/sh`, dispatch on `${0##*/}`, workspace from `pwd -P`, marker-skipping `PATH` search, `-ef "$0"` check, source the env file, `exec`). Make every case in 3.1 pass under dash and macOS `/bin/sh`.
- [x] 3.3 Owner redirect after the first real-cluster run: the `kubectl`, `helm`, `cilium`, and `k9s` wrappers always set `KUBECONFIG` to the absolute `<workspace>/kubeconfig`, overriding any inherited value, on SOCKS and Tailscale clusters; a missing workspace kubeconfig exits 1 with a message naming the file and does not run the real binary; `curl` and `skopeo` leave `KUBECONFIG` unchanged. Write the failing `ToolWrapperScriptTest` cases first (bare call with no `KUBECONFIG`, inherited `KUBECONFIG` overridden, missing kubeconfig, Tailscale still sets it, `curl`/`skopeo` unchanged), then change the wrapper. Update `docs/user-guide/network-connectivity.md` and `docs/development/kits.md` to say a bare `<workspace>/bin/kubectl` works with no setup. Re-run 13.4 on the live cluster with a bare `<workspace>/bin/kubectl get ns` and an exported `KUBECONFIG=~/.kube/config`.

## 4. ToolWrapperInstaller

- [x] 4.1 Write failing tests: writes six executable wrappers and the marker; a second run changes nothing; a changed wrapper is rewritten; `bin/kubectl` without the marker fails with a message naming the file and writes nothing; works when the source checkout is absent (resources come from the classpath).
- [x] 4.2 Implement `ToolWrapperInstaller` on top of `PackagedExecutable`, with the six names and the marker name in `Constants`.

## 5. Kit process launch and resolver removal

- [x] 5.1 Write failing tests for `KitProcessEnvironment.applyTo(builder, workspace, variables)`: `PATH` starts with `<workspace>/bin` followed by the builder's inherited `PATH`; `KUBECONFIG` is the absolute workspace kubeconfig and overrides a relative `KUBECONFIG` in `variables`; a missing kubeconfig fails before the process starts; the wrappers exist afterwards.
- [x] 5.2 Implement `KitProcessEnvironment`.
- [x] 5.3 Add `workspaceDir` to `StepExecutionContext`. Use `KitProcessEnvironment` in `WorkloadStepExecutor.runShellStep`, `KitRunnerCommand.executeScript`, and `KitHookExecutor`. Add tests on the environments built by `WorkloadStepExecutor` (install and phase), `KitInstallCommand`, `KitRunnerCommand`, and `KitHookExecutor`: absolute existing `KUBECONFIG` and `<workspace>/bin` first on `PATH`.
- [x] 5.4 Add a test that a kit install shell step running `kubectl get ns` against a stub `kubectl` that checks `KUBECONFIG` exists succeeds, so `kit install` exits 0.
- [x] 5.5 Delete `services/KubeconfigProxyResolver.kt` and its test, and remove its use from `KitRunnerCommand` and Koin. Confirm no temporary kubeconfig is created. Leave the relative `KUBECONFIG` in `TemplateVariables` for the presto and trino templates.
- [x] 5.6 Grep the tree to confirm nothing sets, clears, or reads and restores `socksProxyHost`/`socksProxyPort`.

## 6. init refusal

- [x] 6.1 Add a typed event for the `bin/` refusal (per `events/CLAUDE.md`, not `Event.Message`/`Event.Error`).
- [x] 6.2 Write failing tests: `init` in a directory with a `bin/` directory fails and writes nothing; the same with a `bin` file; `init --clean` in a workspace whose `bin/` holds only wrappers and the marker succeeds; `init --clean` with a foreign file in `bin/` fails.
- [x] 6.3 Implement the check in `Init` (after the cleanup when `--clean` is set).

## 7. start-socks and stop-socks

- [x] 7.1 Add typed events for tunnel started/reused (with the port), tunnel stopped, and "no tunnel needed on Tailscale".
- [x] 7.2 Write failing tests: `start-socks` starts or reuses the tunnel, records the port, and emits the port; on Tailscale it starts nothing; `stop-socks` stops the tunnel and removes the port while leaving `EDL_TAILSCALE_ACTIVE`.
- [x] 7.3 Implement both top-level commands through `ProcessSocksProxyService`, register them, and add them to help.

## 8. up, down, clean, and VPC restore

- [x] 8.1 `up` (`ClusterConfigurationService.writeSshAndEnvironmentFiles`) writes `EDL_TAILSCALE_ACTIVE` from cluster state and installs the wrappers, on SOCKS and Tailscale clusters. Test the Tailscale path.
- [x] 8.2 `CommandExecutor.ensureProxyRunning` writes `EDL_TAILSCALE_ACTIVE` on its early-return branches where state is known. Test it.
- [x] 8.3 `Down.cleanupSocks5Proxy` removes `EDL_SOCKS_PORT`. Test it.
- [x] 8.4 VPC restore in `CommandExecutor` writes the env file and the wrappers. Test it.
- [x] 8.5 `Clean` deletes the env file, the six wrappers, and the marker by name, and removes `bin/` only if empty. Test both the empty and the foreign-file cases.

## 9. env.sh

- [x] 9.1 Write a bash test that sources the generated `env.sh` from a temp workspace and checks that `type -P kubectl` is `<workspace>/bin/kubectl`, that none of the six names is a function, that `start-socks5`/`stop-socks5`/`socks5-start`/`socks5-stop` are not defined, that re-sourcing after defining the old functions removes them, and that sourcing works under `set -e`.
- [x] 9.2 Edit `configuration/env.sh`: guarded `unset -f` of the six names, then `export PATH="$CLUSTER_DIR/bin:$PATH"`; remove the six functions, `start-socks5`, `stop-socks5`, their aliases, and `is-tailscale-connected` once unused; `_socks5_port` sources the env file (no `jq`, no 1080); `with-proxy` fails with the `start-socks` message when no port is recorded and runs directly on Tailscale; keep `socks5-status`; update help text.
- [x] 9.3 Add a Kotlin test on the `env.sh` content written by `ClusterConfigWriter` (no `jq`, no `.socks5-proxy-state` read, `bin` on `PATH`).
- [ ] 9.4 Check by hand in zsh on the owner's laptop: `source env.sh` twice gives no `unset -f` noise and `kubectl` resolves to the wrapper.

## 10. Repo cleanup

- [x] 10.1 Delete `bin/end-to-end-test`, `bin/e2e-pr`, `bin/debug-log-pipeline`, `bin/test-spark-bulk-writer`, and `bin/submit-direct-bulk-writer`.
- [x] 10.2 Delete `docs/development/end-to-end-testing.md` and its `docs/SUMMARY.md` entry. Update references in `.claude/skills/cluster-ops/SKILL.md`, `.claude/skills/agent-test/SKILL.md`, `.claude/skills/agent-test/README.md`, `.claude/skills/create-kit/SKILL.md`, `docs/development/overview.md`, and `.env.example`.
- [x] 10.3 Remove the root `.gitignore` entries `sshConfig`, `/env.sh`, `state.json`, `/kubeconfig`, and `/.socks5-proxy-state`.
- [x] 10.4 Grep the repo's shell scripts and resources to confirm none parses JSON to read proxy state.

## 11. Docs and CLAUDE.md files

- [x] 11.1 `docs/development/kits.md`: shell steps get `<workspace>/bin` first on `PATH` and an absolute `KUBECONFIG`; which tools are wrapped; that unwrapped tools go direct; that calls bypassing `PATH` are not wrapped.
- [x] 11.2 `docs/user-guide/network-connectivity.md`: wrappers, the env file, `start-socks`/`stop-socks` (browser setup uses the printed port), `command kubectl` now runs the wrapper, Tailscale behavior.
- [x] 11.3 `docs/user-guide/kubernetes.md`: `kubectl` and `k9s` after `source env.sh`.
- [x] 11.4 Root `CLAUDE.md`: in the SOCKS absolute rule, replace "local kubectl/helm in kit shell steps via `SocksTcpBridge` (see `KubeconfigProxyResolver`)" with the tool wrappers; add the env-file standing rule; note that a workspace must not already have a `bin/`.
- [x] 11.5 `providers/CLAUDE.md`: update the reason the `Hostname` line must follow `Host` (the `env.sh` `start-socks5` `grep -A 1` reader is gone; keep or drop the rule per what still reads it).
- [ ] 11.6 Before archiving this change, archive `ssm-ssh-transport`, then add a MODIFIED copy of "SSH transport over SSM Session Manager" to this change's `networking` delta whose "Shell helpers work unchanged over SSM" scenario names `easy-db-lab start-socks` instead of starting the proxy from `env.sh` (see `overrides.md`).
- [x] 11.7 Update any other `CLAUDE.md` that names `KubeconfigProxyResolver`, `start-socks5`, or the e2e scripts.

## 12. Build checks

- [x] 12.1 `./gradlew ktlintFormat`, then `./gradlew test` and `./gradlew detekt` on JDK 21 (run in a subagent). Fix findings with code, never with `@Suppress` or baselines.
- [x] 12.2 `./gradlew integrationTest` (run in a subagent). Raise any TestContainers failure to the owner.

## 13. Real-cluster verification (required)

Run from `./gradlew installDist` output, in a workspace outside the checkout, once on the `direct` transport and once on the `ssm` transport, each on a SOCKS (non-Tailscale) cluster.

- [ ] 13.1 A scratch kit whose install shell step runs `kubectl get ns`, `helm list -A`, `timeout 60 kubectl get ns`, `echo ns | xargs kubectl get`, a `/bin/sh` script and a nested kit `bin/` script that call `kubectl`, `curl` to a private cluster service, and `aws sts get-caller-identity`. `kit install` exits 0, and `aws` sees no proxy variables.
- [ ] 13.2 The same calls in a phase step.
- [ ] 13.3 Kill the tunnel, run a kit command so the proxy restarts on a new port, and confirm the next wrapped call uses it.
- [ ] 13.4 `source env.sh`, then `kubectl get ns` and `k9s` work through the tunnel.
- [ ] 13.5 `stop-socks`, then a wrapped call fails with the `start-socks` message; `start-socks` prints the port and the wrapped call works again.
- [ ] 13.6 `down` removes the port from the env file.
- [ ] 13.7 Regression: a built-in kit with shell steps, and `presto start`.
- [ ] 13.8 On a Tailscale cluster: `source env.sh` right after `up`, then `kubectl get ns` connects directly.
