## Why

Kit shell steps in an `install:` list cannot reach the API server. They get `KUBECONFIG=kubeconfig`, a relative path that resolves against the kit directory, where no kubeconfig exists. On a SOCKS-only cluster the install path also never routes `kubectl` or `helm` through the tunnel. Phase steps work only because `KitRunnerCommand` makes a temporary kubeconfig copy with a `proxy-url` (`KubeconfigProxyResolver`). `env.sh` solves the same problem a third way, with shell functions that read the proxy port with `jq` and fall back to port 1080. The three paths have drifted apart, and none of them covers indirect calls such as `xargs kubectl` or a nested `/bin/sh` script.

This change replaces all three with one mechanism: real executable wrapper scripts on `PATH`, fed by a sourceable env file that the CLI writes.

## What Changes

**Env file (new standing rule)**
- The CLI writes `<workspace>/.socks5-proxy.env`, a sourceable file with two fixed keys: `EDL_TAILSCALE_ACTIVE=true|false` and `EDL_SOCKS_PORT=<port>` (absent when no tunnel is up). One writer class owns the file and merge-updates keys with an atomic write (unique temp file in the same directory, then rename).
- `up` (`ClusterConfigurationService.writeSshAndEnvironmentFiles`) always writes `EDL_TAILSCALE_ACTIVE` from cluster state. This also fixes the Tailscale `up` path, which today returns before writing any proxy state.
- `ProcessSocksProxyService` writes `EDL_SOCKS_PORT` only after the tunnel is verified (start or reuse). `down` (`Down.cleanupSocks5Proxy`) and `stop-socks` remove it. `CommandExecutor.ensureProxyRunning` writes `EDL_TAILSCALE_ACTIVE` on its early-return branches. VPC restore writes the env file and the wrappers. `clean` deletes the env file.
- `.socks5-proxy-state` (JSON) is now also written atomically, which fixes a partial-write bug in `ProcessSocksProxyService`.
- New standing rule in the root `CLAUDE.md`: any value a shell-side tool needs is written by Kotlin to this env file. Shell code never parses JSON.

**Tool wrappers in `<workspace>/bin/`**
- One packaged POSIX `#!/bin/sh` resource is written six times into `<workspace>/bin/` as `kubectl`, `helm`, `cilium`, `curl`, `skopeo`, and `k9s`, with a marker file `.easy-db-lab-tool-wrappers`. It runs under dash and macOS `/bin/sh`, and it is written from distribution resources, so a Homebrew install with no repo works.
- Each wrapper finds the real binary on `PATH` (skipping any directory that holds the marker, and itself), sources the env file on every call, and then:
  - on a Tailscale cluster, runs the real binary with the environment unchanged;
  - on a SOCKS cluster with a recorded port, clears every inherited proxy variable and sets its own (`HTTPS_PROXY=socks5://` for kubectl/helm/cilium/k9s; `ALL_PROXY=socks5h://` plus `NO_PROXY=localhost,127.0.0.1` for curl; `ALL_PROXY`/`HTTP_PROXY`/`HTTPS_PROXY=socks5h://` plus `NO_PROXY` for skopeo), upper and lower case, for that call only;
  - with no port recorded, fails with a message that names the workspace and `easy-db-lab start-socks`.
- New `ToolWrapperInstaller` writes the wrappers at `up`, before every kit process, and at VPC restore. It fails fast and writes nothing if `<workspace>/bin/<tool>` exists without the marker.
- New shared `PackagedExecutable` helper (content compare, unique staged temp file, checked `setExecutable`, atomic rename). `SsmProxyWrapper` moves onto it, which fixes its fixed `.tmp` name race.

**Kit process launch**
- New `KitProcessEnvironment.applyTo(builder, workspace, variables)` installs the wrappers, adds the variables, sets `KUBECONFIG` to the absolute `<workspace>/kubeconfig` (fails fast if missing), and puts `<workspace>/bin` first on `PATH`. `WorkloadStepExecutor.runShellStep` (install and phase shell steps), `KitRunnerCommand.executeScript`, and `KitHookExecutor` all use it. `StepExecutionContext` gains `workspaceDir`.
- `KubeconfigProxyResolver` and its test are deleted. No temporary kubeconfig copies are made. `TemplateVariables` keeps the relative `KUBECONFIG` that the presto and trino templates use.

**Commands**
- `init` fails fast with a typed event and writes nothing when the directory already has a `bin/`. This is what blocks the source checkout or a project directory from being used as a workspace.
- New top-level `easy-db-lab start-socks`: starts or reuses the tunnel, records the port in the env file, and prints the port for browser setup. On a Tailscale cluster it says no tunnel is needed.
- New top-level `easy-db-lab stop-socks`: stops the tunnel without tearing down the cluster and removes the port from the env file.
- Both emit new typed lifecycle events.
- `clean` deletes the env file, the six wrappers, and the marker by name, and removes `bin/` only if it is then empty.

**`env.sh`**
- Puts `$CLUSTER_DIR/bin` first on `PATH`, after a guarded `unset -f` of the six tool names so a re-sourced shell drops old functions.
- Removes the `kubectl`, `helm`, `cilium`, `curl`, `skopeo`, and `k9s` functions, `start-socks5`, `stop-socks5`, the `socks5-start`/`socks5-stop` aliases, and the local `tailscale status` check.
- Keeps `with-proxy` and `socks5-status`. `_socks5_port` sources the env file (no `jq`, no 1080 fallback). `with-proxy` fails like the wrappers when no port is recorded, and runs the command directly on a Tailscale cluster. Help text is updated.

**Repo cleanup**
- Remove `bin/end-to-end-test`, `bin/e2e-pr`, `bin/debug-log-pipeline`, `bin/test-spark-bulk-writer`, and `bin/submit-direct-bulk-writer`.
- Remove `docs/development/end-to-end-testing.md` and its `SUMMARY.md` entry, and update references in `.claude/skills/cluster-ops/SKILL.md`, `.claude/skills/agent-test/SKILL.md`, `.claude/skills/agent-test/README.md`, `.claude/skills/create-kit/SKILL.md`, `docs/development/overview.md`, and `.env.example`.
- Remove root `.gitignore` entries that exist only for a repo-root workspace: `sshConfig`, `/env.sh`, `state.json`, `/kubeconfig`, `/.socks5-proxy-state`.

**Docs**
- `docs/development/kits.md`, `docs/user-guide/network-connectivity.md` (browser setup uses `start-socks` and its printed port; `command kubectl` now runs the wrapper), `docs/user-guide/kubernetes.md`, root `CLAUDE.md` (the SOCKS rule names the wrappers instead of `KubeconfigProxyResolver`; add the env-file standing rule), and `providers/CLAUDE.md` (the `grep -A 1` Hostname reason).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `networking`: MODIFIED "SOCKS Proxy" and "SOCKS Proxy Routes Only Cluster-Internal Traffic"; REMOVED the kubeconfig `proxy-url` requirement; ADDED the env file, the tool wrappers, wrapper routing, transports, `start-socks`/`stop-socks`, and `env.sh` requirements.
- `typed-install-steps`: ADDED the launch environment for kit shell steps.
- `workload-runner`: MODIFIED the script environment (absolute `KUBECONFIG`, wrappers on `PATH`).
- `kit-lifecycle-hooks`: ADDED the launch environment for hook scripts.
- `cluster-lifecycle`: MODIFIED "Cluster Initialization", "Local Cleanup", and "Cluster State Restore".
- `end-to-end-testing`: REMOVED every requirement (the `bin/end-to-end-test` runner is deleted).
- `env-file-config`: MODIFIED to drop `bin/end-to-end-test`.

## Impact

- Kotlin: `services/ClusterConfigurationService.kt`, `services/ProcessSocksProxyService.kt`, `services/CommandExecutor.kt`, `services/WorkloadStepExecutor.kt`, `services/KitHookExecutor.kt`, `commands/install/KitRunnerCommand.kt`, `commands/install/KitInstallCommand.kt`, `commands/Init.kt`, `commands/Down.kt`, `commands/Clean.kt`, `providers/ssm/SsmProxyWrapper.kt`, new `PackagedExecutable`, `ToolWrapperInstaller`, `KitProcessEnvironment`, proxy env file writer, `start-socks`/`stop-socks` commands, and new events in `events/Event.kt`. `services/KubeconfigProxyResolver.kt` is deleted.
- Resources: new `configuration/tool-wrapper.sh`; `configuration/env.sh` rewritten in its proxy section.
- Users: a workspace directory must not already have a `bin/`. The repo root can no longer be a workspace. Shells that sourced an old `env.sh` keep the old functions until they re-source it.
- Wrapped tools send all their traffic through the tunnel, including public URLs such as `helm repo add`. Unwrapped tools, `aws` included, connect directly.
