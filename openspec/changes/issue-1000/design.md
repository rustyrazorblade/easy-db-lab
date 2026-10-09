## Context

Three code paths reach the cluster from local shell tools today, and they disagree:

- Kit install shell steps get `KUBECONFIG=kubeconfig` from `TemplateVariables`, a relative path. `WorkloadStepExecutor.runShellStep` runs them from the kit directory, so the path points at `<workspace>/<kit>/kubeconfig`, which does not exist. Nothing routes them through the tunnel.
- Kit phase shell steps work because `KitRunnerCommand` uses `KubeconfigProxyResolver` to make a temporary kubeconfig copy with a `proxy-url`. Only `kubectl` and `helm` read it.
- `env.sh` defines shell functions (`kubectl()`, `helm()`, `curl()`, and others) that set proxy variables. They read the port with `jq` and fall back to 1080, and they decide Tailscale with a local `tailscale status` check. Shell functions are invisible to `xargs`, `timeout`, and child `/bin/sh` scripts.

The tunnel is an `ssh -N -D` process managed by `ProcessSocksProxyService`, recorded in `.socks5-proxy-state` (JSON). The JVM-global `socksProxyHost`/`socksProxyPort` properties are never used, and this change keeps it that way.

The cluster SSH transport is `direct` (plain SSH) or `ssm`. Under `ssm` the tunnel is the same `ssh -N -D`, reaching the control node through the `sshConfig` ProxyCommand.

## Goals / Non-Goals

**Goals:**
- One mechanism for kit install steps, phase steps, hooks, and interactive `env.sh` shells.
- Indirect calls (`timeout`, `xargs`, `env`, nested scripts) go through the same mechanism.
- Unwrapped tools, `aws` included, never get proxy settings.
- No JSON parsing in shell, and no `jq` requirement.
- Works on both transports, and from a Homebrew install with no repo.

**Non-Goals:**
- A cluster whose transport changes between SOCKS and Tailscale.
- The `ssh`, `scp`, `sftp`, and `rsync` functions in `env.sh`.
- Typed steps (`helm`, `manifest`, `wait`, and so on), which run on the control node or through fabric8.
- Changing built-in kits.
- Covering scripts that bypass `PATH` (`env -i`, an absolute `/usr/local/bin/kubectl`, `PATH=/usr/bin`). The docs say these are not wrapped.

## Decisions

### 1. A sourceable env file is the only shell-facing proxy state

The CLI writes `<workspace>/.socks5-proxy.env` with fixed keys only:

- `EDL_TAILSCALE_ACTIVE=true|false`
- `EDL_SOCKS_PORT=<int>`, present only while a verified tunnel is recorded

One writer class owns the file. It merge-updates keys and writes atomically: a unique temp file in the same directory, then a rename. Two processes in one workspace can write at once; the last rename wins and no reader sees a partial file.

Who writes what:

- `up` (`ClusterConfigurationService.writeSshAndEnvironmentFiles`) always writes `EDL_TAILSCALE_ACTIVE` from `state.json`'s `tailscaleActive`. Today the Tailscale branch of `up` returns before any proxy state is written (`CommandExecutor` returns before the Tailscale branch; `Up` returns without writing), so an `env.sh` user right after a Tailscale `up` has nothing to read. This fixes that.
- `ProcessSocksProxyService` writes `EDL_SOCKS_PORT` only after the tunnel is verified, on start and on reuse.
- `down` (`Down.cleanupSocks5Proxy`) and `stop-socks` remove `EDL_SOCKS_PORT`.
- `CommandExecutor.ensureProxyRunning` writes `EDL_TAILSCALE_ACTIVE` on its early-return branches where cluster state is known.
- VPC restore writes the env file and the wrappers.
- `clean` deletes the file.

`.socks5-proxy-state` stays JSON for the Kotlin side and is now written atomically too, which fixes a partial-write bug at `ProcessSocksProxyService`'s state write.

This becomes a standing rule in the root `CLAUDE.md`: any value that a shell-side tool needs is written by Kotlin to this env file. Shell code never parses JSON.

### 2. Wrapper scripts in `<workspace>/bin/`

One POSIX `#!/bin/sh` resource, `src/main/resources/com/rustyrazorblade/easydblab/configuration/tool-wrapper.sh`, is written six times into `<workspace>/bin/` as `kubectl`, `helm`, `cilium`, `curl`, `skopeo`, and `k9s`, plus a marker file `<workspace>/bin/.easy-db-lab-tool-wrappers`. It must run under dash and macOS `/bin/sh`.

Each call:

1. Dispatches on `${0##*/}`.
2. Resolves the workspace as the parent of the wrapper's own directory, with `pwd -P` so a symlinked workspace resolves.
3. Finds the real binary: the first executable `$tool` on `PATH` whose directory does not hold the marker and which is not `-ef "$0"`. If none is found it prints a message to stderr and exits 127. Skipping marked directories means two workspaces' `bin/` on one `PATH` never call each other.
4. Unsets `EDL_TAILSCALE_ACTIVE` and `EDL_SOCKS_PORT`, then sources the env file, so inherited values never leak in and a port change between calls is picked up.
5. If `EDL_TAILSCALE_ACTIVE=true`, runs `exec "$real" "$@"` with the environment untouched.
6. If no `EDL_SOCKS_PORT` is set, exits 1 with a message that names the workspace and tells the user to run `easy-db-lab start-socks`.
7. Otherwise clears every inherited proxy variable (`HTTP_PROXY`/`http_proxy`, `HTTPS_PROXY`/`https_proxy`, `ALL_PROXY`/`all_proxy`, `NO_PROXY`/`no_proxy`) and sets both cases of:
   - `kubectl`, `helm`, `cilium`, `k9s`: `HTTPS_PROXY=socks5://localhost:$port`.
   - `curl`: `ALL_PROXY=socks5h://localhost:$port`, `NO_PROXY=localhost,127.0.0.1`.
   - `skopeo`: `ALL_PROXY`, `HTTP_PROXY`, `HTTPS_PROXY` = `socks5h://localhost:$port`, `NO_PROXY=localhost,127.0.0.1`.
8. Runs `exec "$real" "$@"`. `PATH` passes on unchanged, so a wrapped tool's children also go through the wrappers.

The proxy variables live only in the wrapper's own process, so `aws` and every other unwrapped tool in the same step get no proxy settings.

### 3. `PackagedExecutable` and `ToolWrapperInstaller`

`PackagedExecutable` lives in the root package beside `ShellQuoting.kt`. It compares content, stages to a unique temp file, checks the result of `setExecutable`, and renames atomically. `SsmProxyWrapper` moves onto it, which fixes its fixed `.tmp` file name race. The existing SSM tests must still pass.

`ToolWrapperInstaller` writes the six wrappers and the marker. It runs at `up`, before every kit process, and at VPC restore. It is idempotent and rewrites a wrapper whose content changed (for example after an upgrade). If `<workspace>/bin/<tool>` exists and the marker does not, it fails fast and writes nothing.

`clean` deletes only the six wrapper files and the marker, by name. It never deletes other files in `bin/`, and removes the directory only if it is then empty.

### 4. `init` refuses a directory that already has `bin/`

`init` fails fast and writes nothing when the directory already has a `bin/` (file or directory). This is the only check that keeps the source checkout or a project directory from being used as a workspace. The message says the directory already has a `bin/`, that easy-db-lab needs `bin/` for its tool wrappers, and to use a new, empty directory as the workspace (for example under `clusters/`). It is a typed event, not `Event.Message`/`Event.Error`.

With `--clean`, the check runs after the cleanup. The cleanup removes the wrappers, the marker, and an empty `bin/`, so a workspace whose `bin/` holds only wrappers can be re-initialized. A `bin/` that still exists after the cleanup fails the check. Without `--clean`, an existing workspace is already refused by the existing-files check.

### 5. One launch seam for kit processes

`KitProcessEnvironment.applyTo(builder: ProcessBuilder, workspace: File, variables: Map<String, String>)`:

- installs the wrappers (idempotent);
- adds `variables` to the builder's environment;
- sets `KUBECONFIG` to the absolute `<workspace>/kubeconfig`, overriding the relative value in `variables`, and fails fast if the file is missing;
- sets `PATH` to `<workspace>/bin` + path separator + the `PATH` already in the builder's environment.

`WorkloadStepExecutor.runShellStep` (install and phase shell steps), `KitRunnerCommand.executeScript`, and `KitHookExecutor` all call it. `StepExecutionContext` gains `workspaceDir`.

`TemplateVariables` keeps the relative `KUBECONFIG`, because the presto and trino templates build `${SCRIPT_DIR}/../../__KUBECONFIG__` from it.

`KubeconfigProxyResolver` and its test are deleted and dropped from `KitRunnerCommand`. No temporary kubeconfig copies are made.

Kit commands that launch these processes are `@RequiresProxy`, so the executor verifies (and if needed restarts) the tunnel before the step runs. A restart on a new port rewrites `EDL_SOCKS_PORT`, and the next wrapped call reads it.

### 6. `start-socks` and `stop-socks`

- `easy-db-lab start-socks` starts or reuses the tunnel through `ProcessSocksProxyService`, writes the port to the env file, and prints the port for browser setup. There is no `--port` option. On a Tailscale cluster it says no tunnel is needed.
- `easy-db-lab stop-socks` stops the tunnel without tearing down the cluster, and removes the port from the env file.

Both are lifecycle commands, so they emit new typed domain events (per `events/CLAUDE.md`). Kotlin is the only proxy manager.

### 7. `env.sh`

- A guarded `unset -f` for `kubectl`, `helm`, `cilium`, `curl`, `skopeo`, and `k9s` (no error in zsh when a name is undefined, and safe under `set -e`), then `export PATH="$CLUSTER_DIR/bin:$PATH"`.
- Removes those six functions, `start-socks5`, `stop-socks5`, the `socks5-start`/`socks5-stop` aliases, and `is-tailscale-connected` once nothing calls it.
- Keeps `with-proxy` and `socks5-status`. `_socks5_port` sources the env file: no `jq`, no 1080 fallback. `with-proxy` fails like the wrappers when no port is recorded, and on a Tailscale cluster runs the command directly.
- Help text updated.

### 8. Transports

Wrappers do not care about the transport. Under both `direct` and `ssm`, the tunnel is `ssh -N -D` using the generated `sshConfig`; under `ssm` the config's ProxyCommand carries it over Session Manager. The wrappers only read the port.

### 9. Repo cleanup

Remove `bin/end-to-end-test`, `bin/e2e-pr`, `bin/debug-log-pipeline`, `bin/test-spark-bulk-writer`, and `bin/submit-direct-bulk-writer`; `docs/development/end-to-end-testing.md` and its `SUMMARY.md` entry; and the root `.gitignore` entries that exist only for a repo-root workspace (`sshConfig`, `/env.sh`, `state.json`, `/kubeconfig`, `/.socks5-proxy-state`). Update every reference to them. The `end-to-end-testing` spec's requirements are removed, and `env-file-config` drops `bin/end-to-end-test`.

### 10. Tests

- Unit tier JUnit (`src/test`) runs the packaged wrapper through real `/bin/sh` with stub binaries, the same way as `SsmProxyWrapperScriptTest`. Cases: each tool's variables; hostile inherited proxy environment; Tailscale leaves the environment untouched; port change between calls; missing port message; missing real binary exits 127; no recursion with two workspaces' `bin/` on `PATH`; symlinked workspace; arguments with spaces, stdin, and exit code passthrough; indirect calls via `env`, `xargs`, `sh -c`, and a nested script; an unwrapped `aws` stub sees no proxy variables; inherited `EDL_*` values are ignored. macOS has no `timeout`, so indirect-call tests use `env`, `xargs`, and `sh -c`.
- Kotlin: `ToolWrapperInstaller` (writes, idempotent, rewrites changed content, refuses a non-wrapper file), `PackagedExecutable`, the env file writer (atomic, merge, remove port), `KitProcessEnvironment` (`PATH` prefix, absolute `KUBECONFIG` overriding the relative one, missing kubeconfig fails), the environments built by `WorkloadStepExecutor`, `KitInstallCommand`, `KitRunnerCommand`, and `KitHookExecutor`, `init` `bin/` refusal, `start-socks`/`stop-socks`, `down` clears the port, `clean`, and `env.sh` content.
- A bash test sources the generated `env.sh` and checks that `type -P kubectl` is the wrapper and that no function shadows it. zsh is checked by hand on the owner's laptop.
- Real cluster (required), on the `direct` transport and on the `ssm` transport, from `installDist` output, in a workspace outside the checkout: a scratch kit whose install step runs `kubectl get ns`, `helm list -A`, indirect calls, `curl` to a private service, and `aws sts get-caller-identity` with no proxy variables; a phase step; a proxy restart on a new port; `source env.sh` then `kubectl` and `k9s`; `start-socks`/`stop-socks`; and a regression run of a built-in kit with shell steps and `presto start`.

## Alternatives Considered

1. **Tailscale detection.** Chosen: cluster state (`tailscaleActive`), passed through the env file. Rejected: a local `tailscale status` check as `env.sh` does today, because it can disagree with the CLI and proxies wrongly when the laptop runs Tailscale for another tailnet. Rejected: an environment variable exported only by Kotlin, because `env.sh` users would not have it.
2. **Reading the port.** The architect recommended `jq` as a hard prerequisite. **Owner override:** no `jq`; Kotlin writes a sourceable env file, and this is now a standing rule. Rejected: parsing the JSON with `sed`, which is brittle and against the `jq` rule.
3. **No port recorded.** Chosen: fail with a clear message (the architect's recommendation). Rejected: fall back to 1080, which can route through another workspace's tunnel.
4. **When to write the wrappers and env file.** Chosen: at `up`, before every kit process, and at VPC restore. The architect recommended `up` plus lazily; the VPC restore point was added after a design-critic finding. Rejected: only at `up`, because they go stale after an upgrade and restored workspaces have none. Rejected: only lazily, because `env.sh` users get none until a kit command runs.
5. **Workspace discovery.** Chosen: relative to the wrapper's own location. Rejected: an `EDL_WORKSPACE` environment variable, which is an extra contract and breaks when unset.
6. **Directory.** The architect recommended `<workspace>/tool-wrappers/`. **Owner override:** `<workspace>/bin/`, because it is standard. A second design-critic pass found the clash with an existing `bin/` (the repo root used as a workspace by `bin/end-to-end-test`; `~/bin` on `PATH`), and a panel was consulted: the architect recommended `bin/` with a fail-fast check; the design critic recommended a hidden `<workspace>/.easy-db-lab/bin/`. The owner chose `bin/` with `init` failing when `bin/` exists, and dropped the repo root as a workspace.
7. **Hooks.** Chosen: include `KitHookExecutor`. Rejected: leave hooks out.
8. **Tests.** Chosen: Kotlin JUnit running the script through `/bin/sh`. Rejected: a standalone `.test.sh` with a Gradle Exec task. A zsh test in CI was rejected by the owner (bash in CI; zsh by hand).
9. **Wrapper form.** Chosen: six identical copies that dispatch on their name. Rejected: small scripts that source a shared library. Rejected: symlinks to one file.
10. **Proxy variables.** Chosen: the wrapper fully controls them (a design-critic finding). Rejected: add-only, as `env.sh` does today, because an inherited `NO_PROXY` bypasses the tunnel and would regress phase steps.
11. **Shell proxy manager.** **Owner override:** remove `env.sh`'s `start-socks5`/`stop-socks5` (Kotlin is the only manager) and add top-level `start-socks`/`stop-socks` commands. Rejected: keep the shell functions and have them write the env file. Rejected: overload `status` to start the tunnel (owner: weird). A `socks` group (`socks start`) was offered; the owner chose top-level `start-socks`. A `--port` option was rejected.
12. **k9s.** Added as a sixth wrapper (the architect called it the owner's call and recommended it). Rejected: leave it as an `env.sh` function.
13. **`PackagedExecutable` shared with `SsmProxyWrapper`.** Folded in (the architect). Rejected: duplicate the logic.

## Risks / Trade-offs

- **All traffic of a wrapped tool goes through the tunnel**, including `helm repo add` and public URLs. It is slower but works. Unwrapped tools, `aws` included, go direct.
- **Calls that bypass `PATH`** (`env -i`, an absolute `/usr/local/bin/kubectl`, `PATH=/usr/bin`) are not wrapped. The kit docs say so.
- **macOS has no `timeout`.** Unit tests of indirect calls use `env`, `xargs`, and `sh -c` instead. The real-cluster check runs `timeout` where it is installed.
- **Concurrent writes** from two processes in one workspace are handled by a unique temp file and a rename.
- **Open shells that sourced an old `env.sh`** keep the old functions until they re-source it. The guarded `unset -f` removes them on re-source.
- **A tunnel that dies on its own** (not through `down` or `stop-socks`) leaves a stale port. An interactive wrapped call gets "connection refused" until the next CLI command or `start-socks`. Kit steps are safe because their launchers are `@RequiresProxy`.
- **zsh `unset -f` noise** when a name is not defined; the guard avoids it.
- **The unarchived `ssm-ssh-transport` change** also edits the networking spec. See `overrides.md`.
