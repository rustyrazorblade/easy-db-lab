## MODIFIED Requirements

### Requirement: SOCKS service access preserves the AWS path

When `exposure` is Socks, the seam SHALL dial the cluster node's private IP at the Service's published NodePort through the per-client SOCKS tunnel. Every command that reads Mimir, Loki, Tempo or Grafana SHALL return the same data as before this change; only port numbers shown to the operator change. The system SHALL NOT set, clear or read the JVM-global `socksProxyHost` or `socksProxyPort` properties; the proxy SHALL be applied per client only.

#### Scenario: AWS reads go through the SOCKS tunnel to the NodePort
- **GIVEN** an AWS cluster with `exposure` Socks and the SOCKS tunnel running
- **WHEN** a CLI command reads Mimir, Loki or Grafana
- **THEN** the request is made through a per-client SOCKS proxy to the node's private IP at that Service's published NodePort, never at the Service's container port

#### Scenario: Teardown flush reaches the backends with no host ports
- **GIVEN** an AWS cluster whose backends declare no container host port
- **WHEN** `down` runs its tail flush
- **THEN** the Loki, Mimir and Tempo flush steps reach their backends through the seam and the flush completes

#### Scenario: No JVM-global proxy property is touched
- **WHEN** any seam implementation builds a client
- **THEN** it sets the proxy on that client only and never reads or writes `socksProxyHost` or `socksProxyPort`

### Requirement: Direct service access uses no proxy

When `exposure` is Direct, the seam SHALL dial the node address at the Service's published NodePort, or the Service address, with no proxy.

#### Scenario: Direct environment bypasses SOCKS
- **GIVEN** a cluster with `exposure` Direct
- **WHEN** a CLI command reads a cluster Service
- **THEN** the connection is made directly to the node address at the published NodePort with no SOCKS proxy, and no SOCKS tunnel is started
