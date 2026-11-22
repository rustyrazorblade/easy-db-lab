## MODIFIED Requirements

### Requirement: Proxy env file for shell-side tools

The CLI MUST write a sourceable file, `.socks5-proxy.env`, in the workspace. It holds only fixed `KEY=value` lines:

- `EDL_TAILSCALE_ACTIVE=true` or `EDL_TAILSCALE_ACTIVE=false`, from the cluster state.
- `EDL_SOCKS_PORT=<port>`, only while a verified tunnel is recorded.
- `EDL_SOCKS_PID=<pid>`, the PID of the recorded `edl-socks-tunnel` script, written and removed together with `EDL_SOCKS_PORT`.
- `EDL_NETWORK_MODE=direct`, only while direct network mode is active (see "Wrappers use no tunnel in direct network mode").
- `EDL_NAMESPACE=<namespace>`, the cluster namespace, written at `init` and `up`.

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
- **WHEN** `down` or `socks stop` stops the tunnel
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


#### Scenario: init and up record the cluster namespace

- **GIVEN** a workspace whose cluster namespace is `lab-a`
- **WHEN** `init` or `up` completes
- **THEN** the env file holds `EDL_NAMESPACE=lab-a`
- **AND** the other keys keep their values
