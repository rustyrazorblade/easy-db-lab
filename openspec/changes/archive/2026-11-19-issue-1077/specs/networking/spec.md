## ADDED Requirements

### Requirement: One network mode per cluster

The cluster state MUST record how this machine reaches the cluster as one value, `networkMode` in `state.json`, with exactly one of three values:

- `socks`: through the SOCKS tunnel the CLI manages.
- `tailscale`: over the tailnet, to the cluster's private addresses.
- `direct`: the cluster API is reachable from this machine as it is, with no tunnel.

`state.json` MUST NOT record the mode in any other field, and every component that needs the mode MUST read it from `networkMode` in the cluster state; no component MAY derive its own mode from the execution environment. The proxy env file holds a copy of it for shell-side tools (see "Proxy env file for shell-side tools"). `init` MUST record `tailscale` when both Tailscale credentials are configured in the user profile and `--no-tailscale` is not given, and `socks` otherwise. The user profile MUST NOT hold a network mode. Nothing in production records `direct` until the local execution environment (#1040) records it.

A tunnel is used only in `socks` mode. In `tailscale` and `direct` mode the CLI MUST NOT start or use the SOCKS tunnel for any connection, and connects directly. Checks and steps that exist for Tailscale itself (the local Tailscale client check, the tailnet route probe in `up`, the Cilium Tailscale masquerade) MUST run only in `tailscale` mode.

#### Scenario: init records tailscale

- **GIVEN** a profile with both Tailscale credentials
- **WHEN** the user runs `init` without `--no-tailscale`
- **THEN** `state.json` holds `"networkMode": "tailscale"` and no `tailscaleActive` field

#### Scenario: init records socks

- **GIVEN** a profile without Tailscale credentials, or `init --no-tailscale`
- **WHEN** `init` writes the cluster state
- **THEN** `state.json` holds `"networkMode": "socks"`

#### Scenario: Direct mode starts no tunnel

- **GIVEN** a cluster state with `"networkMode": "direct"` and infrastructure UP
- **WHEN** a command that requires the proxy runs, or a CQL session or an observability HTTP call is opened
- **THEN** no SOCKS tunnel is started and the connection is made directly

#### Scenario: Direct mode runs no Tailscale step

- **GIVEN** a cluster state with `"networkMode": "direct"`
- **WHEN** `up` or the provisioning preflight runs
- **THEN** the local Tailscale client check, the tailnet route probe and the Cilium Tailscale masquerade do not run

### Requirement: Cluster state from an earlier release is read and rewritten

The CLI MUST read a `state.json` written before `networkMode` existed:

- `tailscaleActive: true` and no `networkMode` reads as `tailscale`.
- `tailscaleActive: false`, or neither field, reads as `socks`.
- When `networkMode` is present, it is used and any `tailscaleActive` field is ignored.

Loading MUST NOT write the file. The next save MUST write `networkMode` and MUST NOT write `tailscaleActive`.

A `networkMode` value other than `socks`, `tailscale` or `direct` MUST fail the load with one line that names `state.json`, the value, and the known values. It applies to every command that loads the state, `down` included, and to the proxy preflight of every `@RequiresProxy` command, which MUST fail rather than skip the tunnel; the value MUST NOT be read as `socks` or any other mode.

#### Scenario: Old Tailscale state

- **GIVEN** a `state.json` with `"tailscaleActive": true` and no `networkMode`
- **WHEN** the CLI loads it
- **THEN** the cluster's network mode is `tailscale`

#### Scenario: Old SOCKS state

- **GIVEN** a `state.json` with `"tailscaleActive": false`, or with neither field
- **WHEN** the CLI loads it
- **THEN** the cluster's network mode is `socks`

#### Scenario: The next save rewrites the old field

- **GIVEN** a `state.json` with `"tailscaleActive": true` and no `networkMode`
- **WHEN** the CLI loads and then saves the state
- **THEN** the file holds `"networkMode": "tailscale"` and no `tailscaleActive`

#### Scenario: Unknown mode in state

- **GIVEN** a `state.json` with `"networkMode": "vpn"`
- **WHEN** the CLI loads it
- **THEN** the load fails with `Unknown networkMode 'vpn' in state.json: the known values are socks, tailscale, direct`

### Requirement: A workspace from an earlier release keeps working

A workspace made by an earlier release has an env file with `EDL_TAILSCALE_ACTIVE` and no `EDL_NETWORK_MODE`, and wrappers in `<workspace>/bin/` that know only `EDL_TAILSCALE_ACTIVE`. Such a workspace MUST keep working with the new build, without the user running anything extra.

- The CLI MUST read the old env file: `EDL_TAILSCALE_ACTIVE=true` as `tailscale`, `EDL_TAILSCALE_ACTIVE=false` as `socks`.
- The CLI MUST write the packaged wrappers into `<workspace>/bin/`, replacing every copy that differs, before every write that records the network mode. It MUST rewrite `<workspace>/env.sh` from the packaged template before every such write from `up` or a VPC restore, and before any such write while the env file holds no `EDL_NETWORK_MODE` or `<workspace>/env.sh` does not exist. The env file MUST be converted only by a write that records the mode. That write MUST replace `EDL_TAILSCALE_ACTIVE` with `EDL_NETWORK_MODE`.
- Recording or removing a tunnel MUST NOT convert the file: it keeps `EDL_TAILSCALE_ACTIVE` as it found it.
- The new wrappers, `with-proxy` and `socks5-status` MUST read an old env file correctly (see "Wrappers branch on the network mode").
- The CLI MUST NOT write `EDL_TAILSCALE_ACTIVE` for a transition. A shell that sourced an `env.sh` from an earlier release keeps that release's `with-proxy` and `socks5-status` functions until the user sources `env.sh` again, which then loads the rewritten `env.sh`; wrapped tools resolve to the new wrappers through `PATH` and are not affected.

#### Scenario: Upgraded Tailscale workspace

- **GIVEN** a Tailscale workspace made by an earlier release, whose env file holds `EDL_TAILSCALE_ACTIVE=true` and whose `bin/` holds the old wrappers
- **WHEN** the new build runs `socks start`, `up`, or any command that requires the proxy
- **THEN** `bin/` holds the packaged wrappers, the env file holds `EDL_NETWORK_MODE=tailscale` and no `EDL_TAILSCALE_ACTIVE`
- **AND** a wrapped `kubectl` connects directly

#### Scenario: Upgraded workspace gets a current env.sh

- **GIVEN** a Tailscale workspace made by an earlier release, whose `env.sh` defines helpers that read only `EDL_TAILSCALE_ACTIVE`
- **WHEN** the new build runs `socks start`, `up`, or any command that requires the proxy
- **THEN** `env.sh` is the packaged template, written before the env file is converted
- **AND** after the user sources `env.sh` again, `with-proxy` runs its command directly

#### Scenario: Upgraded SOCKS workspace with a running tunnel

- **GIVEN** a SOCKS workspace made by an earlier release, whose env file holds `EDL_TAILSCALE_ACTIVE=false` and a recorded tunnel
- **WHEN** the new build runs a command that requires the proxy
- **THEN** the env file holds `EDL_NETWORK_MODE=socks` and the tunnel's port and PID
- **AND** a wrapped `kubectl` goes through the tunnel

#### Scenario: Tunnel writes leave an old file unconverted

- **GIVEN** an env file that holds `EDL_TAILSCALE_ACTIVE=true` and no `EDL_NETWORK_MODE`
- **WHEN** the CLI records or removes a tunnel
- **THEN** the env file still holds `EDL_TAILSCALE_ACTIVE=true` and no `EDL_NETWORK_MODE`

#### Scenario: New wrappers on an old env file

- **GIVEN** an env file that holds `EDL_TAILSCALE_ACTIVE=true` and no `EDL_NETWORK_MODE`, and the new wrappers
- **WHEN** a wrapped tool runs
- **THEN** it runs the real binary with no proxy variable set or cleared, as on a Tailscale cluster

### Requirement: Wrappers branch on the network mode

On every call, each proxy wrapper (`kubectl`, `helm`, `cilium`, `curl`, `skopeo`, `k9s`) MUST unset inherited `EDL_NETWORK_MODE`, `EDL_TAILSCALE_ACTIVE`, `EDL_SOCKS_PORT` and `EDL_SOCKS_PID`, source the workspace's proxy env file, and resolve the mode in this order:

1. `EDL_NETWORK_MODE`, when the file sets it.
2. `tailscale`, when the file sets `EDL_TAILSCALE_ACTIVE=true`.
3. `socks` otherwise, including when there is no env file.

Then:

- `tailscale` or `direct`: the wrapper MUST run the real binary with no proxy variable set or cleared, and MUST NOT require or use a recorded tunnel. The `KUBECONFIG` rule of "Wrappers use the workspace kubeconfig" still applies.
- `socks`: the wrapper MUST behave as "Wrappers route through the tunnel on a SOCKS cluster" states.
- Any other value: the wrapper MUST exit 1, MUST NOT run the real binary, and MUST print `easy-db-lab: unknown network mode '<value>' in <workspace>/.socks5-proxy.env, so <tool> cannot reach the cluster.` followed by `easy-db-lab: run 'easy-db-lab socks start' in <workspace> to record the mode again.`

Only the env file decides: a value exported in the caller's environment MUST have no effect.

#### Scenario: Direct mode runs the real tool with no proxy variables

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=direct` and no recorded tunnel
- **WHEN** a wrapped tool runs
- **THEN** the real binary runs with the environment the wrapper received, apart from `KUBECONFIG` for the four Kubernetes tools
- **AND** the wrapper does not exit with the `socks start` message

#### Scenario: Direct mode ignores a recorded tunnel

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=direct` and a recorded tunnel
- **WHEN** a wrapped tool runs
- **THEN** the real binary sees none of the wrapper's proxy variables

#### Scenario: Direct mode passes inherited proxy variables through

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=direct` and a caller that exports `http_proxy`
- **WHEN** a wrapped tool runs
- **THEN** the real binary sees the inherited `http_proxy` unchanged

#### Scenario: A mode exported in the caller's environment has no effect

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=socks` and no recorded tunnel, and a caller that exports `EDL_NETWORK_MODE=direct`
- **WHEN** a wrapped tool runs
- **THEN** it exits 1 with a message that names the workspace and `easy-db-lab socks start`

#### Scenario: Unknown mode in the env file

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=vpn`
- **WHEN** a wrapped tool runs
- **THEN** it exits 1 with the unknown-network-mode message that names the value, the env file and `easy-db-lab socks start`
- **AND** the real binary does not run

## MODIFIED Requirements

### Requirement: SOCKS Proxy

The system MUST support a SOCKS5 proxy via SSH dynamic port forwarding as the access path to internal cluster services when the cluster's network mode is `socks`. The proxy runs as a detached OS process that persists across JVM restarts, shared across invocations until `down` or `socks stop` is called; its PID and port are recorded in `.socks5-proxy-state`. The proxy state file MUST be written atomically, so a reader never sees a partial file.

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

### Requirement: Proxy env file for shell-side tools

The CLI MUST write a sourceable file, `.socks5-proxy.env`, in the workspace. It holds only fixed `KEY=value` lines:

- `EDL_NETWORK_MODE=socks`, `EDL_NETWORK_MODE=tailscale` or `EDL_NETWORK_MODE=direct`, from the network mode in the cluster state, once the CLI has recorded it.
- `EDL_SOCKS_PORT=<port>`, only while a verified tunnel is recorded.
- `EDL_SOCKS_PID=<pid>`, the PID of the recorded `edl-socks-tunnel` script, written and removed together with `EDL_SOCKS_PORT`.

The CLI MUST NOT write `EDL_TAILSCALE_ACTIVE`. It reads it only in a file written by an earlier release (see "A workspace from an earlier release keeps working").

This file is the only source of proxy state for shell-side tools. Any value that a shell-side tool needs MUST be written by the CLI to this file. No script in the system MAY parse JSON to read proxy state, and `jq` is not required.

The CLI MUST update keys without dropping the others, and MUST write the file atomically, with a unique temporary file in the workspace renamed into place.

The CLI records the network mode at the end of `up`, after a VPC restore, in `socks start`, and before every command that requires the proxy whenever cluster state exists. Each of these writes the packaged wrappers into `<workspace>/bin/` first, and rewrites `env.sh` first as "A workspace from an earlier release keeps working" states.

#### Scenario: up records the network mode

- **WHEN** `up` completes
- **THEN** the env file holds `EDL_NETWORK_MODE` with the value of `networkMode` in cluster state

#### Scenario: Tailscale up leaves a usable env file

- **GIVEN** a cluster with `"networkMode": "tailscale"`
- **WHEN** `up` completes
- **THEN** the env file holds `EDL_NETWORK_MODE=tailscale` and no `EDL_SOCKS_PORT`

#### Scenario: Port written only after the tunnel is verified

- **GIVEN** a cluster in `socks` mode
- **WHEN** the CLI starts or reuses the tunnel and verifies it
- **THEN** the env file holds `EDL_SOCKS_PORT` with the tunnel's port
- **AND** `EDL_NETWORK_MODE` keeps its value

#### Scenario: Port removed when the tunnel stops

- **GIVEN** an env file that records a port
- **WHEN** `down` or `socks stop` stops the tunnel
- **THEN** the env file no longer holds `EDL_SOCKS_PORT`
- **AND** `EDL_NETWORK_MODE` keeps its value

#### Scenario: Command executor records the mode when it skips the proxy

- **GIVEN** cluster state exists with network mode `tailscale` or `direct`, and the executor decides not to start the proxy
- **WHEN** a CLI command that requires the proxy runs
- **THEN** the env file holds `EDL_NETWORK_MODE` with that mode

#### Scenario: Recording the mode keeps the tunnel

- **GIVEN** an env file that records a tunnel
- **WHEN** the CLI records the network mode
- **THEN** the env file still holds `EDL_SOCKS_PORT` and `EDL_SOCKS_PID` with their values

#### Scenario: Concurrent writers never leave a partial file

- **WHEN** two CLI processes in one workspace update the env file at the same time
- **THEN** a reader sources a complete file each time
- **AND** the file holds only the fixed keys

#### Scenario: No shell code parses JSON for proxy state

- **WHEN** the repository's shell scripts and packaged shell resources are searched
- **THEN** none reads `.socks5-proxy-state` or parses JSON to learn the proxy port or the network mode

### Requirement: Wrappers route through the tunnel on a SOCKS cluster

In `socks` mode (see "Wrappers branch on the network mode"), when a port is recorded, the wrapper MUST clear every inherited proxy variable (`HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`, `NO_PROXY`, upper and lower case) and set its own, in both cases, for its own call only:

- `kubectl`, `helm`, `cilium`, `k9s`: `HTTPS_PROXY=socks5://127.0.0.1:<port>`.
- `curl`: `ALL_PROXY=socks5h://127.0.0.1:<port>` and `NO_PROXY=localhost,127.0.0.1`.
- `skopeo`: `ALL_PROXY`, `HTTP_PROXY`, and `HTTPS_PROXY` set to `socks5h://127.0.0.1:<port>`, and `NO_PROXY=localhost,127.0.0.1`.

When no port is recorded, or the recorded `EDL_SOCKS_PID` is not a live process, the wrapper MUST fail with exit code 1 and a message that names the workspace and `easy-db-lab socks start`, and MUST NOT use the port.

#### Scenario: Wrapped tools reach the cluster through the tunnel

- **GIVEN** a SOCKS cluster with a recorded port
- **WHEN** a shell step runs `kubectl`, `helm`, `cilium`, `curl`, or `skopeo`
- **THEN** the call resolves to the wrapper and reaches the cluster through the tunnel with that tool's proxy variables

#### Scenario: Indirect calls go through the wrapper

- **GIVEN** a SOCKS cluster with a recorded port
- **WHEN** a shell step runs a wrapped tool through `timeout`, `xargs`, `env`, a nested script in a kit `bin/`, or a `/bin/sh` script
- **THEN** the call goes through the wrapper and through the tunnel

#### Scenario: Inherited proxy variables do not bypass the tunnel

- **GIVEN** a SOCKS cluster with a recorded port and an operator environment that sets `NO_PROXY`, `no_proxy`, `http_proxy`, or similar variables
- **WHEN** a wrapped tool runs
- **THEN** the real binary sees only the wrapper's proxy variables
- **AND** the call goes through the tunnel

#### Scenario: Recorded tunnel process is gone

- **GIVEN** an env file that records a port and an `EDL_SOCKS_PID` whose process has exited
- **WHEN** a wrapped tool runs
- **THEN** it exits 1 with a message that names the workspace and `easy-db-lab socks start`
- **AND** it does not connect to the recorded port

#### Scenario: Inherited EDL values are ignored

- **GIVEN** an environment that already sets `EDL_SOCKS_PORT`, `EDL_SOCKS_PID`, `EDL_NETWORK_MODE` or `EDL_TAILSCALE_ACTIVE`
- **WHEN** a wrapped tool runs
- **THEN** the wrapper uses only the values in the workspace's env file

#### Scenario: New port after a proxy restart

- **GIVEN** the tunnel restarts on a new port during a session and the CLI records it
- **WHEN** the next wrapped call runs
- **THEN** it uses the new port

#### Scenario: No port recorded

- **GIVEN** a SOCKS cluster whose env file records no port
- **WHEN** a wrapped tool runs
- **THEN** it exits 1 with a message that names the workspace and `easy-db-lab socks start`
- **AND** it does not fall back to a default port

#### Scenario: Tunnel died on its own

- **GIVEN** a recorded port whose tunnel process has died without `down` or `socks stop`
- **WHEN** a kit command that runs shell steps is invoked
- **THEN** the CLI verifies or restarts the tunnel and records its port before the first step runs

### Requirement: Wrappers connect directly on a Tailscale cluster

In `tailscale` mode (see "Wrappers branch on the network mode"), each wrapper MUST run the real binary with the environment unchanged, except that the `kubectl`, `helm`, `cilium`, and `k9s` wrappers set `KUBECONFIG` as the "Wrappers use the workspace kubeconfig" requirement states. No proxy variable is set or cleared.

#### Scenario: Tailscale cluster

- **GIVEN** a cluster with `"networkMode": "tailscale"`
- **WHEN** a wrapped tool runs in a kit shell step or in a shell that sourced `env.sh`
- **THEN** the real binary runs with the same environment the wrapper received, apart from `KUBECONFIG` for the four Kubernetes tools
- **AND** it connects directly

#### Scenario: env.sh right after a Tailscale up

- **GIVEN** `up` has just completed on a Tailscale cluster and no other command has run
- **WHEN** the user sources `env.sh` and runs `kubectl get ns`
- **THEN** `kubectl` connects directly and succeeds

### Requirement: socks start and socks stop commands

The CLI MUST provide a `socks` command group with `socks start` and `socks stop` subcommands, following the project's command-group convention (for example `tailscale start`). Both emit typed lifecycle events.

- `socks start` records the network mode in the proxy env file (writing the wrappers first), then in `socks` mode starts or reuses the tunnel, records its port, and prints the port so the user can set up a browser. It has no `--port` option. In `tailscale` or `direct` mode it emits `Proxy.TunnelNotNeeded` with that mode, reports that no tunnel is needed, and starts nothing.
- `socks stop` stops the tunnel without tearing down the cluster and removes the port from the proxy env file.

#### Scenario: socks start on a SOCKS cluster

- **GIVEN** a SOCKS cluster with no tunnel running
- **WHEN** the user runs `easy-db-lab socks start`
- **THEN** the tunnel starts, the env file records its port, and the port is printed

#### Scenario: socks start reuses a running tunnel

- **GIVEN** a SOCKS cluster with a verified tunnel running
- **WHEN** the user runs `easy-db-lab socks start`
- **THEN** no new tunnel starts and the existing port is recorded and printed

#### Scenario: socks start on a Tailscale cluster

- **GIVEN** a cluster with `"networkMode": "tailscale"`
- **WHEN** the user runs `easy-db-lab socks start`
- **THEN** it reports that the cluster uses Tailscale and no tunnel is needed, and starts nothing

#### Scenario: socks start in direct mode

- **GIVEN** a cluster with `"networkMode": "direct"`
- **WHEN** the user runs `easy-db-lab socks start`
- **THEN** it reports that the cluster is reachable from this machine and no tunnel is needed, and starts nothing
- **AND** the env file holds `EDL_NETWORK_MODE=direct`

#### Scenario: socks stop

- **GIVEN** a running tunnel
- **WHEN** the user runs `easy-db-lab socks stop`
- **THEN** the tunnel process stops, the env file no longer records a port, and the cluster keeps running
- **AND** the next wrapped call fails with the `easy-db-lab socks start` message

### Requirement: env.sh puts the workspace tool wrappers on PATH

`env.sh` MUST put `<workspace>/bin` first on `PATH`, so `kubectl`, `helm`, `cilium`, `curl`, `skopeo`, `k9s`, `ssh`, `scp`, `sftp`, and `rsync` resolve to the same wrappers that kit shell steps use. It MUST first remove any shell function of those ten names left by an earlier `env.sh`, without errors in bash or zsh and without failing under `set -e`. It MUST NOT define those functions and MUST NOT start or stop the proxy.

`with-proxy` and `socks5-status` stay. They read the network mode and the port from the proxy env file only, resolve the mode exactly as the wrappers do (see "Wrappers branch on the network mode"), and ignore values inherited from the shell.

- `with-proxy` in `tailscale` or `direct` mode runs its command with no proxy variable set or cleared, needs no recorded tunnel and uses none that is recorded. In `socks` mode it fails with the same `easy-db-lab socks start` message as the wrappers when no port is recorded. With an unknown mode it prints the wrappers' unknown-network-mode message and returns 1.
- `socks5-status` reports `This cluster uses Tailscale: no SOCKS5 tunnel is needed.` in `tailscale` mode, `This workspace uses direct network mode: the cluster is reached with no SOCKS5 tunnel.` in `direct` mode, and the recorded tunnel in `socks` mode, each exiting 0. With an unknown mode it prints the unknown-network-mode message and returns 1.

#### Scenario: Sourced shell resolves the wrappers

- **WHEN** a user sources `env.sh` in bash
- **THEN** `type -P kubectl` is `<workspace>/bin/kubectl`
- **AND** no shell function named `kubectl`, `helm`, `cilium`, `curl`, `skopeo`, or `k9s` exists

#### Scenario: Sourced shell on a SOCKS cluster

- **GIVEN** a SOCKS cluster with a recorded port
- **WHEN** a user sources `env.sh` and runs `kubectl get ns` or `k9s`
- **THEN** the tool runs through the wrapper and through the tunnel

#### Scenario: Re-sourcing replaces old functions

- **GIVEN** a shell that sourced an older `env.sh` that defined `kubectl()` and the other functions
- **WHEN** the user sources the new `env.sh`
- **THEN** the old functions are gone and the names resolve to the wrappers

#### Scenario: Old proxy functions are gone

- **WHEN** a user sources `env.sh`
- **THEN** `start-socks5`, `stop-socks5`, `socks5-start`, and `socks5-stop` are not defined

#### Scenario: with-proxy with no port recorded

- **GIVEN** a SOCKS cluster whose env file records no port
- **WHEN** the user runs `with-proxy curl http://10.0.1.50:8080/`
- **THEN** it fails with a message that names `easy-db-lab socks start`

#### Scenario: with-proxy on a Tailscale cluster

- **GIVEN** a cluster with `"networkMode": "tailscale"`
- **WHEN** the user runs `with-proxy <command>`
- **THEN** the command runs with no proxy variables added

#### Scenario: with-proxy in direct network mode

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=direct`, with or without a recorded tunnel, and a shell that exports `http_proxy`
- **WHEN** the user runs `with-proxy <command>`
- **THEN** the command runs with no proxy variables added or removed, and sees the inherited `http_proxy` unchanged

#### Scenario: with-proxy on an old env file

- **GIVEN** an env file that holds `EDL_TAILSCALE_ACTIVE=true` and no `EDL_NETWORK_MODE`
- **WHEN** the user runs `with-proxy <command>`
- **THEN** the command runs with no proxy variables added

#### Scenario: socks5-status in direct network mode

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=direct`
- **WHEN** the user runs `socks5-status`
- **THEN** it reports that the workspace uses direct network mode and exits 0

#### Scenario: The helpers ignore an inherited EDL_NETWORK_MODE

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=socks` and no recorded tunnel, and a shell that exports `EDL_NETWORK_MODE=direct`
- **WHEN** the user runs `with-proxy <command>` or `socks5-status`
- **THEN** `with-proxy` fails with the `easy-db-lab socks start` message, and `socks5-status` reports that no tunnel is recorded

#### Scenario: The helpers reject an unknown mode

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=vpn`
- **WHEN** the user runs `with-proxy <command>` or `socks5-status`
- **THEN** it prints the unknown-network-mode message and returns 1
- **AND** `with-proxy` does not run the command
