## ADDED Requirements

### Requirement: Local Tailscale detection at init
The system SHALL detect whether Tailscale is running on the local machine during `init` by running `tailscale status --json` via a subprocess and checking that `BackendState` equals `"Running"`. The result SHALL be stored as `tailscaleActive` in `state.json`. If the `tailscale` binary is not installed or the subprocess fails, the system SHALL treat Tailscale as inactive (`false`).

#### Scenario: Tailscale is running at init time
- **WHEN** the user runs `init` and `tailscale status --json` returns `BackendState: "Running"`
- **THEN** `state.json` is written with `tailscaleActive: true`

#### Scenario: Tailscale is not installed at init time
- **WHEN** the user runs `init` and the `tailscale` binary is not found
- **THEN** `state.json` is written with `tailscaleActive: false` and no error is shown

#### Scenario: Tailscale is installed but not connected at init time
- **WHEN** the user runs `init` and `tailscale status --json` returns a non-Running `BackendState`
- **THEN** `state.json` is written with `tailscaleActive: false`

### Requirement: SOCKS proxy bypassed when Tailscale active
When `tailscaleActive` is `true` in cluster state, the system SHALL NOT start or use the SOCKS proxy for any cluster connection. This applies to CQL (Cassandra Java driver), HTTP (Victoria metrics and logs via OkHttp), and Kubernetes API (Fabric8 client). All connections SHALL use the cluster node's private IP directly.

#### Scenario: CQL connection with Tailscale active
- **WHEN** `tailscaleActive` is `true` and a CQL command is executed
- **THEN** no SOCKS proxy is started and the Cassandra driver connects directly to private IPs on port 9042

#### Scenario: HTTP connection with Tailscale active
- **WHEN** `tailscaleActive` is `true` and Victoria metrics or logs are queried
- **THEN** the OkHttp client has no proxy configured and connects directly to the private IP

#### Scenario: Kubernetes API connection with Tailscale active
- **WHEN** `tailscaleActive` is `true` and any K8s operation is performed
- **THEN** the Fabric8 client has no `httpsProxy` set and connects directly to the K3s API on the private IP

### Requirement: SOCKS proxy used unchanged when Tailscale inactive
When `tailscaleActive` is `false` (the default for all existing clusters), the system SHALL behave identically to pre-change behavior: SOCKS proxy is started on demand and used for all cluster connections.

#### Scenario: Existing cluster state without tailscaleActive field
- **WHEN** an existing `state.json` is loaded that has no `tailscaleActive` field
- **THEN** the system treats it as `false` and uses the SOCKS proxy as before
