## MODIFIED Requirements

### Requirement: SOCKS Proxy

The system MUST support a SOCKS5 proxy via SSH dynamic port forwarding as the access path to internal cluster services when Tailscale is not active. The proxy runs as a detached OS process that persists across JVM restarts, shared across invocations until `down` or `stop-socks` is called; its PID and port are recorded in `.socks5-proxy-state`. The proxy state file MUST be written atomically, so a reader never sees a partial file.

The proxy MUST be started eagerly by the command executor rather than lazily by individual services: when cluster state exists, infrastructure is UP, and Tailscale is not active, the executor SHALL start (or reuse) the proxy before any command logic executes. Because starting the proxy is idempotent and the process persists, startup MUST be attempted unconditionally on every command invocation under those conditions, and no individual service SHALL be responsible for starting it.

When `tailscaleActive` is `true` in cluster state, the SOCKS proxy SHALL NOT be started or used; all traffic connects directly to cluster private IPs.

The CLI is the only component that starts or stops the proxy. `env.sh` and the tool wrappers MUST NOT start or stop it. Shell-side tools learn the active port only from the proxy env file, which the CLI updates each time it verifies, starts, or stops the tunnel.

#### Scenario: Proxy starts before command logic

- **GIVEN** a provisioned cluster with infrastructure UP and Tailscale not active
- **WHEN** any CLI command is invoked
- **THEN** the SOCKS5 proxy is started (or reused if already running) before any command logic executes

#### Scenario: Proxy started as a detached process when Tailscale is not configured

- **GIVEN** a cluster whose `state.json` has `tailscaleActive: false`
- **WHEN** any component needs to reach internal cluster services
- **THEN** a SOCKS5 proxy is started via `ssh -N -D <port> -F sshConfig control0` as a detached OS process
- **AND** its PID and port are written to `.socks5-proxy-state`
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

### Requirement: SOCKS Proxy Routes Only Cluster-Internal Traffic

The SOCKS5 proxy MUST route only cluster-internal traffic (the K3s API and other private cluster services). It MUST NOT capture traffic to public endpoints — in particular AWS SDK calls (S3, EC2, IAM, STS) MUST connect directly, out the host's normal network path, regardless of whether the proxy is running.

The system MUST NOT enable the proxy by setting the standard JVM-global `socksProxyHost`/`socksProxyPort` properties, because those route every socket the process opens through the tunnel. Instead, the active proxy port is published privately, and only the clients that need the tunnel configure the SOCKS proxy explicitly: in the CLI, the Kubernetes client and the cluster HTTP client; on the shell side, the workspace tool wrappers, each for its own call only.

On the shell side, only the wrapped tools (`kubectl`, `helm`, `cilium`, `curl`, `skopeo`, `k9s`) use the tunnel. A wrapped tool sends all of its traffic through the tunnel, public URLs included. Every other tool, `aws` included, gets no proxy settings from the system and connects directly.

#### Scenario: AWS calls go direct while the proxy is running

- **GIVEN** a provisioned cluster with infrastructure UP and the SOCKS5 proxy active
- **WHEN** the CLI makes an AWS SDK call (e.g. S3 backup, EC2 describe, IAM policy update)
- **THEN** the call connects directly to the AWS endpoint and is not routed through the SSH tunnel.

#### Scenario: Cluster-internal traffic still uses the tunnel

- **GIVEN** the SOCKS5 proxy is active and Tailscale is not enabled
- **WHEN** the CLI reaches the K3s API or a private cluster service (e.g. Mimir or Loki on the control node)
- **THEN** that traffic is routed through the SOCKS proxy via explicit per-client configuration.

#### Scenario: Tailscale active means no proxy and all-direct routing

- **GIVEN** Tailscale is enabled for the cluster, so the SOCKS5 proxy is never started and the proxy port is not published
- **WHEN** the CLI reaches the K3s API, a private cluster service, or any AWS endpoint
- **THEN** every connection is direct — the K8s client and the cluster HTTP client both select no proxy, using Tailscale's private network for cluster traffic.

#### Scenario: State backup/restore works on a network that blocks the tunnel path

- **GIVEN** a network where the account S3 bucket is reachable directly from the operator's machine but the cluster tunnel cannot reach it
- **WHEN** the CLI backs up or restores cluster state to/from S3
- **THEN** the S3 traffic connects directly and succeeds, because it is never forced through the tunnel.

#### Scenario: Unwrapped tools in a shell step get no proxy settings

- **GIVEN** a SOCKS cluster with a recorded port and the operator's environment holding no proxy variables
- **WHEN** a kit shell step runs `aws sts get-caller-identity` or any other tool that is not wrapped
- **THEN** that tool's environment holds no `HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`, or `NO_PROXY` variable in either case
- **AND** it connects directly

#### Scenario: Shell-side routing never touches the JVM proxy properties

- **WHEN** the CLI installs the wrappers, writes the proxy env file, or launches a kit shell step, phase script, or hook
- **THEN** the JVM-global `socksProxyHost` and `socksProxyPort` properties are not set, cleared, or read and restored at any point

## REMOVED Requirements

### Requirement: Local kubectl/helm invoked by kit shell steps route through the tunnel via a per-command kubeconfig proxy-url

**Reason**: Only phase steps used it, only `kubectl` and `helm` read it, and it needed a temporary kubeconfig copy per command. The workspace tool wrappers on `PATH` replace it for install steps, phase steps, hooks, and `env.sh`, and they also cover `curl`, `skopeo`, `cilium`, `k9s`, and indirect calls.

**Migration**: Kit shell steps get the absolute workspace kubeconfig, unmodified, and `<workspace>/bin` first on `PATH`; see "Kit shell steps run with the workspace tool wrappers and an absolute kubeconfig" in `typed-install-steps` and "Workspace tool wrappers" here. No temporary kubeconfig is created.

## ADDED Requirements

### Requirement: Proxy env file for shell-side tools

The CLI MUST write a sourceable file, `.socks5-proxy.env`, in the workspace. It holds only fixed `KEY=value` lines:

- `EDL_TAILSCALE_ACTIVE=true` or `EDL_TAILSCALE_ACTIVE=false`, from the cluster state.
- `EDL_SOCKS_PORT=<port>`, only while a verified tunnel is recorded.

This file is the only source of proxy state for shell-side tools. Any value that a shell-side tool needs MUST be written by the CLI to this file. No script in the system MAY parse JSON to read proxy state, and `jq` is not required.

The CLI MUST update keys without dropping the others, and MUST write the file atomically, with a unique temporary file in the workspace renamed into place.

#### Scenario: up records the Tailscale flag

- **WHEN** `up` completes on a SOCKS cluster or on a Tailscale cluster
- **THEN** the env file holds `EDL_TAILSCALE_ACTIVE` with the value of `tailscaleActive` in cluster state

#### Scenario: Tailscale up leaves a usable env file

- **GIVEN** a cluster with `tailscaleActive: true`
- **WHEN** `up` completes
- **THEN** the env file holds `EDL_TAILSCALE_ACTIVE=true` and no `EDL_SOCKS_PORT`

#### Scenario: Port written only after the tunnel is verified

- **GIVEN** a SOCKS cluster
- **WHEN** the CLI starts or reuses the tunnel and verifies it
- **THEN** the env file holds `EDL_SOCKS_PORT` with the tunnel's port
- **AND** `EDL_TAILSCALE_ACTIVE` keeps its value

#### Scenario: Port removed when the tunnel stops

- **GIVEN** an env file that records a port
- **WHEN** `down` or `stop-socks` stops the tunnel
- **THEN** the env file no longer holds `EDL_SOCKS_PORT`
- **AND** `EDL_TAILSCALE_ACTIVE` keeps its value

#### Scenario: Command executor records the Tailscale flag when it skips the proxy

- **GIVEN** cluster state exists and the executor decides not to start the proxy because Tailscale is active
- **WHEN** a CLI command runs
- **THEN** the env file holds `EDL_TAILSCALE_ACTIVE=true`

#### Scenario: Concurrent writers never leave a partial file

- **WHEN** two CLI processes in one workspace update the env file at the same time
- **THEN** a reader sources a complete file each time
- **AND** the file holds only the fixed keys

#### Scenario: No shell code parses JSON for proxy state

- **WHEN** the repository's shell scripts and packaged shell resources are searched
- **THEN** none reads `.socks5-proxy-state` or parses JSON to learn the proxy port or the Tailscale state

### Requirement: Workspace tool wrappers

The CLI MUST write executable wrapper scripts named `kubectl`, `helm`, `cilium`, `curl`, `skopeo`, and `k9s` into `<workspace>/bin/`, plus a marker file `.easy-db-lab-tool-wrappers` in that directory. The wrappers MUST come from resources packaged in the distribution, so they work from a Homebrew install with no source checkout. They MUST run under the POSIX `/bin/sh` of both Linux (dash) and macOS.

The CLI MUST write them at `up`, before every kit process it launches, and when it restores a workspace from a VPC. Writing MUST be idempotent, MUST replace a wrapper whose content changed, and MUST be atomic. If `<workspace>/bin/` holds one of the six names and has no marker, the CLI MUST fail with a clear message and write nothing.

A call that bypasses `PATH` (an absolute path to the real binary, `env -i`, or a replaced `PATH`) is not wrapped, and the kit docs MUST say so.

#### Scenario: Wrappers written at up

- **WHEN** `up` completes
- **THEN** `<workspace>/bin/` holds the six executable wrappers and the marker

#### Scenario: Wrappers written from a Homebrew install

- **GIVEN** the tool is installed with Homebrew and no repository exists on the machine
- **WHEN** `up` runs or a kit process is launched
- **THEN** the six wrappers are written from packaged resources and work

#### Scenario: Wrappers rewritten when their content changed

- **GIVEN** a workspace whose wrappers were written by an older version
- **WHEN** a kit process is launched
- **THEN** each wrapper whose content differs from the packaged script is replaced
- **AND** a wrapper that already matches is left as it is

#### Scenario: A foreign file blocks the wrappers

- **GIVEN** `<workspace>/bin/kubectl` exists and `<workspace>/bin/` has no marker
- **WHEN** the CLI installs the wrappers
- **THEN** it fails with a message naming the file
- **AND** it writes no wrapper and no marker

#### Scenario: Wrappers written on VPC restore

- **WHEN** the CLI restores a workspace from a VPC
- **THEN** the wrappers, the marker, and the proxy env file are written

#### Scenario: Calls that bypass PATH are not wrapped

- **WHEN** a script runs a tool by its absolute path, under `env -i`, or with `PATH` replaced
- **THEN** the wrapper does not run for that call
- **AND** the kit docs state this limit

### Requirement: Wrappers run the real binary, never themselves

Each wrapper MUST pick its tool from its own file name and MUST run the first executable of that name on `PATH` that is not in a directory holding the wrapper marker and is not the wrapper itself. It MUST find its workspace from its own location, with symlinks resolved. It MUST pass arguments, stdin, and the exit code through unchanged, and MUST pass `PATH` on unchanged so the tool's children also use the wrappers.

#### Scenario: Wrapper runs the real binary

- **GIVEN** `<workspace>/bin` first on `PATH` and a real `kubectl` later on `PATH`
- **WHEN** `kubectl get ns` runs
- **THEN** the wrapper runs the real `kubectl` with `get ns`

#### Scenario: Two workspaces on PATH do not call each other

- **GIVEN** the `bin/` directories of two workspaces both on `PATH`, ahead of the real binary
- **WHEN** a wrapped tool runs
- **THEN** the real binary runs once, with no recursion

#### Scenario: Real binary missing

- **WHEN** a wrapper runs and no real binary of its name is on `PATH`
- **THEN** it prints a message naming the tool to stderr and exits 127

#### Scenario: Symlinked workspace

- **GIVEN** a workspace reached through a symlink
- **WHEN** a wrapper runs
- **THEN** it finds and reads the workspace's env file

#### Scenario: Arguments, stdin, and exit code pass through

- **WHEN** a wrapped tool runs with arguments that contain spaces, reads stdin, and exits non-zero
- **THEN** the real binary gets the same arguments and stdin
- **AND** the wrapper exits with the real binary's exit code

### Requirement: Wrappers route through the tunnel on a SOCKS cluster

On every call, each wrapper MUST ignore inherited `EDL_TAILSCALE_ACTIVE` and `EDL_SOCKS_PORT` values and source the workspace's proxy env file. When `EDL_TAILSCALE_ACTIVE` is not `true` and a port is recorded, the wrapper MUST clear every inherited proxy variable (`HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`, `NO_PROXY`, upper and lower case) and set its own, in both cases, for its own call only:

- `kubectl`, `helm`, `cilium`, `k9s`: `HTTPS_PROXY=socks5://localhost:<port>`.
- `curl`: `ALL_PROXY=socks5h://localhost:<port>` and `NO_PROXY=localhost,127.0.0.1`.
- `skopeo`: `ALL_PROXY`, `HTTP_PROXY`, and `HTTPS_PROXY` set to `socks5h://localhost:<port>`, and `NO_PROXY=localhost,127.0.0.1`.

When no port is recorded, the wrapper MUST fail with exit code 1 and a message that names the workspace and `easy-db-lab start-socks`.

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

#### Scenario: Inherited EDL values are ignored

- **GIVEN** an environment that already sets `EDL_SOCKS_PORT` or `EDL_TAILSCALE_ACTIVE`
- **WHEN** a wrapped tool runs
- **THEN** the wrapper uses only the values in the workspace's env file

#### Scenario: New port after a proxy restart

- **GIVEN** the tunnel restarts on a new port during a session and the CLI records it
- **WHEN** the next wrapped call runs
- **THEN** it uses the new port

#### Scenario: No port recorded

- **GIVEN** a SOCKS cluster whose env file records no port
- **WHEN** a wrapped tool runs
- **THEN** it exits 1 with a message that names the workspace and `easy-db-lab start-socks`
- **AND** it does not fall back to a default port

#### Scenario: Tunnel died on its own

- **GIVEN** a recorded port whose tunnel process has died without `down` or `stop-socks`
- **WHEN** a kit command that runs shell steps is invoked
- **THEN** the CLI verifies or restarts the tunnel and records its port before the first step runs

### Requirement: Wrappers connect directly on a Tailscale cluster

When the env file holds `EDL_TAILSCALE_ACTIVE=true`, each wrapper MUST run the real binary with the environment unchanged.

#### Scenario: Tailscale cluster

- **GIVEN** a cluster with `tailscaleActive: true`
- **WHEN** a wrapped tool runs in a kit shell step or in a shell that sourced `env.sh`
- **THEN** the real binary runs with the same environment the wrapper received
- **AND** it connects directly

#### Scenario: env.sh right after a Tailscale up

- **GIVEN** `up` has just completed on a Tailscale cluster and no other command has run
- **WHEN** the user sources `env.sh` and runs `kubectl get ns`
- **THEN** `kubectl` connects directly and succeeds

### Requirement: Wrappers work on both SSH transports

The tool wrappers and the proxy env file MUST behave the same under the `direct` and `ssm` SSH transports. Under both, the tunnel is `ssh -N -D` using the generated SSH configuration; under `ssm` that configuration carries the connection over Session Manager.

#### Scenario: SSM transport

- **GIVEN** a SOCKS cluster on the `ssm` transport
- **WHEN** a kit shell step or an `env.sh` shell runs a wrapped tool
- **THEN** the call reaches the cluster through the tunnel, the same as on the `direct` transport

#### Scenario: Direct transport

- **GIVEN** a SOCKS cluster on the `direct` transport
- **WHEN** a kit shell step or an `env.sh` shell runs a wrapped tool
- **THEN** the call reaches the cluster through the tunnel

### Requirement: start-socks and stop-socks commands

The CLI MUST provide top-level `start-socks` and `stop-socks` commands. Both emit typed lifecycle events.

- `start-socks` starts or reuses the tunnel, records its port in the proxy env file, and prints the port so the user can set up a browser. It has no `--port` option. On a Tailscale cluster it reports that no tunnel is needed and starts nothing.
- `stop-socks` stops the tunnel without tearing down the cluster and removes the port from the proxy env file.

#### Scenario: start-socks on a SOCKS cluster

- **GIVEN** a SOCKS cluster with no tunnel running
- **WHEN** the user runs `easy-db-lab start-socks`
- **THEN** the tunnel starts, the env file records its port, and the port is printed

#### Scenario: start-socks reuses a running tunnel

- **GIVEN** a SOCKS cluster with a verified tunnel running
- **WHEN** the user runs `easy-db-lab start-socks`
- **THEN** no new tunnel starts and the existing port is recorded and printed

#### Scenario: start-socks on a Tailscale cluster

- **GIVEN** a cluster with `tailscaleActive: true`
- **WHEN** the user runs `easy-db-lab start-socks`
- **THEN** it reports that no tunnel is needed and starts nothing

#### Scenario: stop-socks

- **GIVEN** a running tunnel
- **WHEN** the user runs `easy-db-lab stop-socks`
- **THEN** the tunnel process stops, the env file no longer records a port, and the cluster keeps running
- **AND** the next wrapped call fails with the `easy-db-lab start-socks` message

### Requirement: env.sh puts the workspace tool wrappers on PATH

`env.sh` MUST put `<workspace>/bin` first on `PATH`, so `kubectl`, `helm`, `cilium`, `curl`, `skopeo`, and `k9s` resolve to the same wrappers that kit shell steps use. It MUST first remove any shell function of those six names left by an earlier `env.sh`, without errors in bash or zsh and without failing under `set -e`. It MUST NOT define those functions and MUST NOT start or stop the proxy.

`with-proxy` and `socks5-status` stay. They read the port from the proxy env file. `with-proxy` fails with the same `easy-db-lab start-socks` message as the wrappers when no port is recorded, and runs its command directly on a Tailscale cluster.

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
- **THEN** it fails with a message that names `easy-db-lab start-socks`

#### Scenario: with-proxy on a Tailscale cluster

- **GIVEN** a cluster with `tailscaleActive: true`
- **WHEN** the user runs `with-proxy <command>`
- **THEN** the command runs with no proxy variables added
