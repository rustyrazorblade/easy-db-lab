## MODIFIED Requirements

### Requirement: SOCKS Proxy

The system MUST support a SOCKS5 proxy via SSH dynamic port forwarding as the access path to internal cluster services when Tailscale is not active. The proxy runs as a detached OS process that persists across JVM restarts, shared across invocations until `down` or `socks stop` is called; its PID and port are recorded in `.socks5-proxy-state`. The proxy state file MUST be written atomically, so a reader never sees a partial file.

The proxy MUST be started eagerly by the command executor rather than lazily by individual services: when cluster state exists, infrastructure is UP, and Tailscale is not active, the executor SHALL start (or reuse) the proxy before any command logic executes. Because starting the proxy is idempotent and the process persists, startup MUST be attempted unconditionally on every command invocation under those conditions, and no individual service SHALL be responsible for starting it.

When `tailscaleActive` is `true` in cluster state, the SOCKS proxy SHALL NOT be started or used; all traffic connects directly to cluster private IPs.

When the recorded execution environment resolves to the `direct` network mode (K3s in Docker, or a cluster the user brings), the SOCKS proxy SHALL NOT be started, required, or used:

- The executor starts no tunnel for `@RequiresProxy` commands.
- `socks start` reports that no tunnel is needed.
- The Kubernetes client is configured with no proxy.
- Cluster Services are reached through the recorded service exposure.

The executor MUST re-assert `EDL_NETWORK_MODE=direct` in the proxy env file from the recorded environment before command logic runs. The recorded environment, never the env file, decides the network mode.

The CLI is the only component that starts or stops the proxy. `env.sh` and the tool wrappers MUST NOT start or stop it. Shell-side tools learn the active port only from the proxy env file, which the CLI updates each time it verifies, starts, or stops the tunnel.

#### Scenario: Proxy starts before command logic

- **GIVEN** a provisioned cluster with infrastructure UP and Tailscale not active
- **WHEN** any CLI command is invoked
- **THEN** the SOCKS5 proxy is started (or reused if already running) before any command logic executes

#### Scenario: Proxy started as a detached process when Tailscale is not configured

- **GIVEN** a cluster whose `state.json` has `tailscaleActive: false`
- **WHEN** any component needs to reach internal cluster services
- **THEN** a SOCKS5 proxy is started as a detached OS process through `<workspace>/bin/edl-socks-tunnel`, which runs `ssh -N -D 127.0.0.1:<port> -F sshConfig control0`
- **AND** the PID of `edl-socks-tunnel` and the port are written to `.socks5-proxy-state`
- **AND** once the tunnel is verified, its port is written to the proxy env file

#### Scenario: Proxy reused across invocations

- **GIVEN** `.socks5-proxy-state` exists with a live PID, matching `controlIP`, and matching `sshConfig` path
- **WHEN** a new easy-db-lab invocation calls `ensureRunning()`
- **THEN** the existing SSH process is reused rather than a new one being started
- **AND** its port is written to the proxy env file

#### Scenario: Stale proxy replaced without user intervention

- **GIVEN** `.socks5-proxy-state` exists but the recorded PID is no longer alive
- **WHEN** any CLI command is invoked next
- **THEN** a new SSH proxy process is started automatically and `.socks5-proxy-state` is updated
- **AND** the proxy env file records the new port

#### Scenario: Proxy skipped when Tailscale is configured

- **GIVEN** a cluster whose `state.json` has `tailscaleActive: true`
- **WHEN** any component needs to reach internal cluster services
- **THEN** no SOCKS proxy is started and connections are made directly to cluster private IPs

#### Scenario: Tailscale detection is profile-based

- **WHEN** the user runs `init`
- **THEN** `tailscaleActive` is set to `true` if and only if `tailscaleClientId` and `tailscaleClientSecret` are both configured in the user profile
- **AND** the `--no-tailscale` flag overrides this, forcing `tailscaleActive: false` regardless of credentials

#### Scenario: Proxy skipped when cluster is not provisioned

- **GIVEN** no cluster state file exists or infrastructure is not UP
- **WHEN** a CLI command is invoked
- **THEN** the SOCKS5 proxy is not started

#### Scenario: Proxy persists for session lifetime

- **GIVEN** the REPL or server is running and `tailscaleActive` is `false`
- **WHEN** the proxy is needed
- **THEN** it persists for the lifetime of the session rather than per-command

#### Scenario: Shell tools read the port from the env file

- **GIVEN** a running cluster whose proxy env file records the active port
- **WHEN** the user sources `env.sh` and runs a wrapped tool or `with-proxy`
- **THEN** the port comes from the proxy env file written by the CLI
- **AND** `env.sh` does not start the proxy, does not read the JSON state file, and does not fall back to a default port

#### Scenario: Proxy state file is written atomically

- **WHEN** the CLI writes `.socks5-proxy-state`
- **THEN** it writes a temporary file in the workspace and renames it into place
- **AND** a concurrent reader sees either the old content or the new content, never a partial file

#### Scenario: Proxy cleaned up on teardown

- **GIVEN** `.socks5-proxy-state` exists with an active PID
- **WHEN** `down` is run
- **THEN** the SSH proxy process is killed and `.socks5-proxy-state` is deleted
- **AND** the port is removed from the proxy env file

#### Scenario: A proxy-requiring command in a local workspace connects directly

- **GIVEN** a `k3s-docker` workspace whose cluster is running
- **WHEN** a `@RequiresProxy` command runs
- **THEN** no SOCKS tunnel is started and none is required
- **AND** the Kubernetes client reaches the API server with no proxy configured

#### Scenario: socks start in a local workspace

- **GIVEN** a `k3s-docker` workspace
- **WHEN** the user runs `socks start`
- **THEN** it reports that no tunnel is needed and starts none

#### Scenario: The env file follows the recorded environment

- **GIVEN** a `k3s-docker` workspace whose env file has lost `EDL_NETWORK_MODE`
- **WHEN** any command runs
- **THEN** the env file holds `EDL_NETWORK_MODE=direct` before command logic runs

#### Scenario: An AWS workspace keeps its tunnel

- **GIVEN** an `ec2-k3s` workspace with infrastructure UP and Tailscale not active
- **WHEN** any command runs
- **THEN** the tunnel is started or reused as today, and the env file holds no `EDL_NETWORK_MODE`

### Requirement: Wrappers use no tunnel in direct network mode

A workspace MAY record that the cluster API is reachable from this machine with no tunnel. The CLI records it as `EDL_NETWORK_MODE=direct` in the proxy env file, through `ProxyEnvFile.recordDirect`, and removes the key when direct mode is turned off. The key MUST be kept by every other update of the env file (recording or removing a tunnel, recording the Tailscale flag), because the file is rewritten whole from its known fields. `EDL_TAILSCALE_ACTIVE` MUST NOT be reused for this mode.

Only the env file decides. Each wrapper MUST unset an inherited `EDL_NETWORK_MODE` before it sources the env file, so a value exported in the caller's environment has no effect. When the env file holds `EDL_NETWORK_MODE=direct`, each wrapper MUST run the real binary with no proxy variable set or cleared, and MUST NOT require a recorded tunnel, exactly as on a Tailscale cluster, including the `KUBECONFIG` rule of the "Wrappers use the workspace kubeconfig" requirement. Proxy variables the caller exported (`HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`, `NO_PROXY`, upper and lower case, for example a corporate `http_proxy`) MUST pass through to the real binary untouched, as on a Tailscale cluster. `with-proxy` and `socks5-status` in `env.sh` follow the same mode (see "env.sh puts the workspace tool wrappers on PATH"). When the key is absent, the env file and the wrappers MUST behave as they did before this mode existed.

`init --local` writes the key. The command executor re-asserts it from the recorded execution environment, so a workspace whose environment resolves to the direct network mode always holds it, and one that does not never does. `init` MUST NOT read the key to decide anything.

#### Scenario: A local workspace's wrappers set no proxy variables

- **GIVEN** a workspace created by `init --local`
- **WHEN** any `bin/` wrapper runs
- **THEN** no proxy variable is set by the wrapper
- **AND** no SOCKS tunnel is started or required

#### Scenario: Direct mode runs the real tool with no proxy variables

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=direct` and no recorded tunnel
- **WHEN** a wrapped tool runs
- **THEN** the real binary runs with the environment the wrapper received, apart from `KUBECONFIG` for the four Kubernetes tools
- **AND** the wrapper does not exit with the `socks start` message

#### Scenario: Direct mode ignores a recorded tunnel

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=direct` and a recorded tunnel
- **WHEN** a wrapped tool runs
- **THEN** the real binary sees none of the wrapper's proxy variables

#### Scenario: The key survives tunnel and Tailscale updates

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=direct`
- **WHEN** the CLI records a tunnel, removes the tunnel, or records the Tailscale flag
- **THEN** the env file still holds `EDL_NETWORK_MODE=direct`

#### Scenario: Turning direct mode off removes the key

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=direct` and other keys
- **WHEN** the CLI records direct mode as inactive
- **THEN** the env file no longer holds `EDL_NETWORK_MODE`
- **AND** the other keys keep their values

#### Scenario: A value exported in the caller's environment has no effect

- **GIVEN** an env file that does not hold `EDL_NETWORK_MODE=direct`, and a caller that exports `EDL_NETWORK_MODE=direct`
- **WHEN** a wrapped tool runs
- **THEN** it exits 1 with a message that names the workspace and `easy-db-lab socks start`

#### Scenario: No key, no change

- **GIVEN** an env file with no `EDL_NETWORK_MODE`
- **WHEN** a wrapped tool runs
- **THEN** the wrapper behaves as the "Wrappers route through the tunnel on a SOCKS cluster" and "Wrappers connect directly on a Tailscale cluster" requirements state

