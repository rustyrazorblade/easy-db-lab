## MODIFIED Requirements

### Requirement: SOCKS Proxy

The system MUST support a SOCKS5 proxy via SSH dynamic port forwarding as the access path to internal cluster services when the cluster's network mode is `socks`. The proxy runs as a detached OS process that persists across JVM restarts, shared across invocations until `socks stop` is called or a `down` that includes the workspace's own cluster stops it (see "Teardown of the workspace's own cluster" in `cluster-teardown`); a `down` of another network, `down --packer`, a declined prompt, a dry run and a `down` with nothing recorded MUST leave it running; its PID and port are recorded in `.socks5-proxy-state`. The proxy state file MUST be written atomically, so a reader never sees a partial file.

The proxy MUST be started eagerly by the command executor rather than lazily by individual services: when cluster state exists, infrastructure is UP, and the network mode is `socks`, the executor SHALL start (or reuse) the proxy before any command logic executes. Because starting the proxy is idempotent and the process persists, startup MUST be attempted unconditionally on every command invocation under those conditions, and no individual service SHALL be responsible for starting it.

When the network mode in cluster state is `tailscale` or `direct`, the SOCKS proxy SHALL NOT be started or used; all traffic connects directly.

The CLI is the only component that starts or stops the proxy. `env.sh` and the tool wrappers MUST NOT start or stop it. Shell-side tools learn the active port only from the proxy env file, which the CLI updates each time it verifies, starts, or stops the tunnel.

#### Scenario: Proxy starts before command logic

- **GIVEN** a provisioned cluster with infrastructure UP and network mode `socks`
- **WHEN** any CLI command is invoked
- **THEN** the SOCKS5 proxy is started (or reused if already running) before any command logic executes

#### Scenario: Proxy started as a detached process in socks mode

- **GIVEN** a cluster whose `state.json` has `"networkMode": "socks"`
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

#### Scenario: Proxy skipped in tailscale or direct mode

- **GIVEN** a cluster whose `state.json` has `"networkMode": "tailscale"` or `"networkMode": "direct"`
- **WHEN** any component needs to reach internal cluster services
- **THEN** no SOCKS proxy is started and connections are made directly

#### Scenario: Tailscale detection is profile-based

- **WHEN** the user runs `init`
- **THEN** `networkMode` is set to `tailscale` if and only if `tailscaleClientId` and `tailscaleClientSecret` are both configured in the user profile, and to `socks` otherwise
- **AND** the `--no-tailscale` flag overrides this, forcing `socks` regardless of credentials

#### Scenario: Proxy skipped when cluster is not provisioned

- **GIVEN** no cluster state file exists or infrastructure is not UP
- **WHEN** a CLI command is invoked
- **THEN** the SOCKS5 proxy is not started

#### Scenario: Proxy persists for session lifetime

- **GIVEN** the REPL or server is running and the network mode is `socks`
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

### Requirement: SOCKS tunnel reconnects on its own

The CLI MUST start the SOCKS tunnel through a packaged POSIX script, `<workspace>/bin/edl-socks-tunnel`, written with the tool wrappers. The script MUST run `ssh -N -D 127.0.0.1:<port> -F <sshConfig> <host>` with `ExitOnForwardFailure=yes` (IPv4 loopback only, so no other process can hold the port on `::1` and split it), wait for it to exit, and start it again on the same port after a short backoff, for as long as the script runs. The script MUST restart `ssh` only when the workspace's proxy env file records the script's own port as `EDL_SOCKS_PORT`; the CLI writes that port only after it verifies the tunnel end to end, so a recorded port means the tunnel came up. When the env file does not record its port, the tunnel never came up, and the script MUST exit with `ssh`'s status and not restart it, so a start that cannot succeed (a port already in use, a refused key, a changed host key) still fails fast and the CLI can retry on a new port. No timer decides this. When a restarted `ssh` cannot bind its port (for example, another process took it during the backoff), the script MUST exit after that one attempt and MUST NOT loop. The script MUST stop only on TERM; it MUST ignore INT, so a Ctrl-C in the terminal that launched an easy-db-lab command does not end the shared tunnel. Each line the script writes to the tunnel log MUST start with a UTC timestamp, and the script MUST log a line before each `ssh` run. The CLI MUST append to the tunnel log on each launch, with a timestamped header line, and MUST NOT truncate it. When a fresh tunnel's `ssh` is still alive at the end of the verify window and never connected, the CLI's error MUST name the host alias, the address and port `ssh` was dialing (or, when `ssh` reaches the node through a ProxyCommand, as on `ssm`, that it was connecting through it), and the wait.

The CLI MUST choose the tunnel's local port at random from free ports and MUST NOT prefer a fixed port, so two workspaces are unlikely to want the same port. Because the port does not change, the proxy env file stays correct across a reconnect, and wrapped tools in an open shell work again once the connection comes back. The SSH keepalives in the generated `sshConfig` (every transport) make `ssh` exit when a connection dies, so the script can reconnect.

The PID recorded in `.socks5-proxy-state` MUST be the script's. The CLI MUST verify that a recorded PID is an `edl-socks-tunnel` process for the recorded port and `sshConfig` before it signals it. `socks stop`, a `down` that includes the workspace's own cluster, and the replacement of a stale tunnel MUST stop the script and its `ssh` child, and MUST leave no `ssh` process of that tunnel running. The CLI still verifies a reused tunnel end to end, and replaces it when the check fails.

#### Scenario: A dropped connection reconnects on the same port

- **GIVEN** a running tunnel whose `ssh` exits because its connection died
- **WHEN** the connection to the control node is available again
- **THEN** `edl-socks-tunnel` starts `ssh` again on the same port within a few seconds
- **AND** a wrapped tool in a shell that sourced `env.sh` works again with no CLI command

#### Scenario: A first start that fails is not retried by the script

- **GIVEN** a tunnel whose port the env file does not record yet, because the CLI has not verified it
- **WHEN** its `ssh` exits, for example because its port is already in use
- **THEN** the script exits with the same status and does not start `ssh` again
- **AND** the CLI reports the failed start or retries on a new port

#### Scenario: A fresh tunnel still connecting when the verify window ends names the stage

- **GIVEN** a fresh tunnel whose `ssh` is still alive when the CLI's verify window ends
- **AND** its part of the tunnel log shows `ssh` started to connect but never logged that the connection was established
- **WHEN** the CLI reports the failed start
- **THEN** the error names the host alias, the address and port `ssh` was dialing (or, when `ssh` reaches the node through a ProxyCommand, as on `ssm`, that it was connecting through it), and how long the CLI waited
- **AND** on the `direct` transport, when the cluster is not open (`init --open`), the error says the cluster's security group allows SSH only from the public IP recorded when `up` ran
- **AND** the error still names the tunnel log, and still quotes the non-debug lines `ssh` wrote

#### Scenario: A verified tunnel reconnects even right after it started

- **GIVEN** a tunnel that the CLI verified and recorded in the env file a few seconds ago
- **WHEN** its `ssh` exits
- **THEN** the script starts `ssh` again on the same port

#### Scenario: A restart that cannot bind its port ends the script

- **GIVEN** a verified tunnel whose port another process took while its `ssh` was in backoff
- **WHEN** the restarted `ssh` fails to bind the port
- **THEN** the script exits after that one attempt and does not loop
- **AND** wrapped tools fail with the `easy-db-lab socks start` message and never use the port

#### Scenario: Ctrl-C does not end the shared tunnel

- **GIVEN** a running tunnel started by an easy-db-lab command in a terminal
- **WHEN** the user presses Ctrl-C in that terminal during a later easy-db-lab command
- **THEN** the tunnel script and its `ssh` keep running

#### Scenario: The tunnel log keeps the history of every launch

- **GIVEN** a tunnel log from an earlier tunnel
- **WHEN** the CLI launches a new tunnel, or the script restarts `ssh`
- **THEN** the earlier lines remain, and each new line starts with a UTC timestamp

#### Scenario: A replaced tunnel is reported with its new port

- **GIVEN** a recorded tunnel that fails the reuse check
- **WHEN** any CLI command replaces it
- **THEN** the CLI emits a typed event that names the old port, the new port, and the reason
- **AND** if the old tunnel does not stop, the CLI emits a typed error event that names its PID and how to end it

#### Scenario: A killed script leaves no orphaned ssh behind

- **GIVEN** a tunnel script killed from outside with KILL, whose `ssh` child still runs with the recorded port and `sshConfig`
- **WHEN** the CLI next starts or replaces the tunnel, or `socks stop` or a `down` that includes the workspace's own cluster runs
- **THEN** that orphaned `ssh` is stopped

#### Scenario: Stopping the tunnel stops the loop and its ssh

- **WHEN** `socks stop`, or a `down` that includes the workspace's own cluster, stops the tunnel
- **THEN** the `edl-socks-tunnel` process and its `ssh` child have both exited
- **AND** no new `ssh` is started afterwards

#### Scenario: The script is written with the wrappers

- **WHEN** `up` completes, a kit process is launched, or a workspace is restored from a VPC
- **THEN** `<workspace>/bin/edl-socks-tunnel` exists, is executable, and matches the packaged script

#### Scenario: A reused PID that is not the tunnel script is not signaled

- **GIVEN** `.socks5-proxy-state` records a PID now held by a process that is not `edl-socks-tunnel` for the recorded port
- **WHEN** the CLI stops or replaces the tunnel
- **THEN** it does not signal that process

#### Scenario: A down of another network leaves the tunnel running

- **GIVEN** a running tunnel in a workspace with a running cluster
- **WHEN** the operator runs `down <vpc-id>` for a different VPC, or `down --packer`, or declines the `down` prompt
- **THEN** the `edl-socks-tunnel` process and its `ssh` child are still running on the recorded port
