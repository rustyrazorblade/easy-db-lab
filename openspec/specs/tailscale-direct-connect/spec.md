# tailscale-direct-connect Specification

## Purpose
How a cluster in the `tailscale` network mode is reached: `init` records the mode from the profile's Tailscale credentials, and every cluster connection then goes to private IPs over the tailnet with no SOCKS proxy. The network mode itself (`socks`, `tailscale`, `direct`) is defined in the networking spec.
## Requirements
### Requirement: Local Tailscale detection at init
The system SHALL decide at `init` whether the cluster is reached over Tailscale from the user profile: when both `tailscaleClientId` and `tailscaleClientSecret` are configured and `--no-tailscale` is not given, `init` SHALL record `"networkMode": "tailscale"` in `state.json`; otherwise it SHALL record `"networkMode": "socks"`. `init` SHALL NOT run the local `tailscale` binary to make this decision; whether the local client is connected is checked by `up` (see the networking spec).

#### Scenario: Tailscale credentials configured at init time
- **WHEN** the user runs `init` with both Tailscale credentials in the profile and without `--no-tailscale`
- **THEN** `state.json` is written with `"networkMode": "tailscale"`

#### Scenario: No Tailscale credentials at init time
- **WHEN** the user runs `init` and the profile lacks either Tailscale credential
- **THEN** `state.json` is written with `"networkMode": "socks"` and no error is shown

#### Scenario: --no-tailscale at init time
- **WHEN** the user runs `init --no-tailscale` with both Tailscale credentials in the profile
- **THEN** `state.json` is written with `"networkMode": "socks"`

### Requirement: SOCKS proxy bypassed in tailscale mode
When the network mode in cluster state is `tailscale`, the system SHALL NOT start or use the SOCKS proxy for any cluster connection. This applies to CQL (Cassandra Java driver), HTTP (Mimir, Loki and the other control-node services via OkHttp), and Kubernetes API (Fabric8 client). All connections SHALL use the cluster node's private IP directly.

#### Scenario: CQL connection in tailscale mode
- **WHEN** the network mode is `tailscale` and a CQL command is executed
- **THEN** no SOCKS proxy is started and the Cassandra driver connects directly to private IPs on port 9042

#### Scenario: HTTP connection in tailscale mode
- **WHEN** the network mode is `tailscale` and Mimir or Loki is queried
- **THEN** the OkHttp client has no proxy configured and connects directly to the private IP

#### Scenario: Kubernetes API connection in tailscale mode
- **WHEN** the network mode is `tailscale` and any K8s operation is performed
- **THEN** the Fabric8 client has no `httpsProxy` set and connects directly to the K3s API on the private IP

### Requirement: SOCKS proxy used unchanged in socks mode
When the network mode is `socks` (the mode of every cluster whose state predates Tailscale direct connect), the system SHALL behave identically to pre-change behavior: SOCKS proxy is started on demand and used for all cluster connections.

#### Scenario: Existing cluster state without a mode field
- **WHEN** an existing `state.json` is loaded that has neither `networkMode` nor `tailscaleActive`
- **THEN** the system treats it as `socks` and uses the SOCKS proxy as before

