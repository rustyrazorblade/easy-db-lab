## Why

The SOCKS5 proxy is currently started lazily — each service that needs it calls `ensureRunning`
individually (`K8sClientProvider`, `CqlSessionService`, `K3sService`, `VictoriaStreamService`,
`VictoriaMetricsQueryService`, `KitStatusCommand`). This means the first service that happens
to need the proxy pays the startup cost, the proxy is only started on code paths that explicitly
call it, and if the OS process is killed between invocations, recovery depends on which service
runs first.

`env.sh` also manages proxy startup independently: it calls `start-socks5` at source time, runs
the same PID-alive / IP-match validation already in `ProcessSocksProxyService`, and hardcodes
`SOCKS5_PROXY_PORT=1080` rather than reading the actual port from the state file. This duplicates
logic and means the shell environment can have a different port than the Kotlin CLI.

Since `ensureRunning` is idempotent and the process persists as a detached OS process, there is
no cost to starting the proxy eagerly. Running it unconditionally at command startup ensures it is
always available, removes the scattered service-level calls, and removes the need for `env.sh` to
manage the proxy lifecycle at all.

## What Changes

- `DefaultCommandExecutor.executeTopLevel()` calls `socksProxyService.ensureRunning(controlHost)`
  early in its lifecycle — after VPC reconstruction but before `executeWithLifecycle` — when the
  cluster state exists, infrastructure is UP, and `isTailscaleEnabled()` is false. The control
  host is read from `ClusterState.getControlHost()`.
- All per-service `ensureRunning` calls are removed: `K8sClientProvider`, `K3sService`,
  `VictoriaStreamService`, `VictoriaMetricsQueryService`, `CqlSessionService`, and
  `KitStatusCommand`.
- `env.sh` template is updated: the `start-socks5` call at the end of the file is removed. The
  `SOCKS5_PROXY_PORT` variable is populated by reading the `port` field from
  `.socks5-proxy-state` (using `jq`) rather than being hardcoded to `1080`. All existing shell
  wrappers (`kubectl`, `k9s`, `curl`, `skopeo`, `helm`, `cilium`, `with-proxy`) are unchanged —
  they continue to use `$SOCKS5_PROXY_PORT`.

## Capabilities

### Modified Capabilities

- `networking`: SOCKS5 proxy startup moves from lazy (per-service) to eager (per-command-invocation).
  The proxy is guaranteed to be running before any command logic executes, as long as the cluster
  is provisioned, infrastructure is UP, and Tailscale is not enabled. `env.sh` no longer starts
  the proxy; it reads the port from the state file written by `ProcessSocksProxyService`.

## Impact

- `services/CommandExecutor.kt` — add `SocksProxyService` injection to `DefaultCommandExecutor`;
  add proxy startup call in `executeTopLevel()` guarded by state-exists + infra-UP +
  !tailscaleEnabled
- `services/K8sClientProvider.kt` — remove `ensureRunning` call
- `services/K3sService.kt` — remove `ensureRunning` call
- `services/VictoriaStreamService.kt` — remove `ensureRunning` calls
- `services/VictoriaMetricsQueryService.kt` — remove `ensureRunning` call
- `services/CqlSessionService.kt` — remove `ensureRunning` call
- `commands/install/KitStatusCommand.kt` — remove `ensureRunning` call
- `src/main/resources/.../configuration/env.sh` — remove `start-socks5` invocation at end of
  file; read `SOCKS5_PROXY_PORT` from `.socks5-proxy-state` via `jq` with fallback to `1080`
- `openspec/specs/networking/spec.md` — update REQ-NET-005 to reflect eager startup behavior
- Tests: `DefaultCommandExecutorTest` — verify proxy is started when infra is UP and Tailscale
  is disabled; verify proxy is skipped when Tailscale is enabled; verify proxy is skipped when
  no state file exists
