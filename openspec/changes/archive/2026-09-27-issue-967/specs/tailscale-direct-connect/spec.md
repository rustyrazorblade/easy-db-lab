## MODIFIED Requirements

### Requirement: SOCKS proxy bypassed when Tailscale active
When `tailscaleActive` is `true` in cluster state, the system SHALL NOT start or use the SOCKS proxy for any cluster connection. This applies to CQL (Cassandra Java driver), HTTP (Mimir, Loki and the other control-node services via OkHttp), and Kubernetes API (Fabric8 client). All connections SHALL use the cluster node's private IP directly.

#### Scenario: CQL connection with Tailscale active
- **WHEN** `tailscaleActive` is `true` and a CQL command is executed
- **THEN** no SOCKS proxy is started and the Cassandra driver connects directly to private IPs on port 9042

#### Scenario: HTTP connection with Tailscale active
- **WHEN** `tailscaleActive` is `true` and Mimir or Loki is queried
- **THEN** the OkHttp client has no proxy configured and connects directly to the private IP

#### Scenario: Kubernetes API connection with Tailscale active
- **WHEN** `tailscaleActive` is `true` and any K8s operation is performed
- **THEN** the Fabric8 client has no `httpsProxy` set and connects directly to the K3s API on the private IP
