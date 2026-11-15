## ADDED Requirements

### Requirement: Service access seam with environment-selected implementation

The system SHALL reach every cluster Service it reads through one service-access seam that opens a connection to a named Kubernetes Service and port and returns an endpoint the caller dials. The seam SHALL have three implementations — Socks, Direct and PortForward — and the system SHALL select one from the `exposure` recorded in cluster state. No CLI client SHALL build a node address or choose a proxy route itself.

#### Scenario: Implementation chosen from recorded exposure

- **GIVEN** a workspace whose cluster state records an `exposure`
- **WHEN** a CLI command opens a cluster Service through the seam
- **THEN** the seam uses the Socks implementation when `exposure` is Socks, the Direct implementation when it is Direct, and the PortForward implementation when it is PortForward

#### Scenario: Exposure defaults to Socks

- **GIVEN** a workspace whose cluster state records no `exposure`
- **WHEN** a CLI command opens a cluster Service
- **THEN** the seam uses the Socks implementation, so an AWS cluster's behaviour is unchanged

### Requirement: SOCKS service access preserves the AWS path

When `exposure` is Socks, the seam SHALL dial the cluster node's private IP and port through the per-client SOCKS tunnel, and the output of every command that reads Mimir, Loki, Tempo or Grafana SHALL be unchanged from before this change. The system SHALL NOT set, clear or read the JVM-global `socksProxyHost` or `socksProxyPort` properties; the proxy SHALL be applied per client only.

#### Scenario: AWS reads go through the SOCKS tunnel unchanged

- **GIVEN** an AWS cluster with `exposure` Socks and the SOCKS tunnel running
- **WHEN** a CLI command reads Mimir, Loki or Grafana
- **THEN** the request is made through a per-client SOCKS proxy to the node's private IP, and the command's output is identical to before this change

#### Scenario: No JVM-global proxy property is touched

- **WHEN** any seam implementation builds a client
- **THEN** it sets the proxy on that client only and never reads or writes `socksProxyHost` or `socksProxyPort`

### Requirement: Direct service access uses no proxy

When `exposure` is Direct, the seam SHALL dial the node or Service address with no proxy.

#### Scenario: Direct environment bypasses SOCKS

- **GIVEN** a cluster with `exposure` Direct
- **WHEN** a CLI command reads a cluster Service
- **THEN** the connection is made directly to the address with no SOCKS proxy, and no SOCKS tunnel is started

### Requirement: Port-forward service access bound to loopback and closed with the command

When `exposure` is PortForward, the seam SHALL open a Fabric8 port-forward to the named Service bound to `127.0.0.1` only, on a local port from the loopback port range (20000–29999). The forward SHALL be closed when the command ends. The seam SHALL verify the Service exists, and SHALL fail fast naming the namespace, the Service, the port and the Kubernetes error when a port-forward cannot be opened.

#### Scenario: Port-forward bound to loopback only

- **GIVEN** a cluster with `exposure` PortForward
- **WHEN** a CLI command opens a cluster Service
- **THEN** a port-forward to that Service is bound to `127.0.0.1` on a local port in 20000–29999, and the caller dials `127.0.0.1:<local port>`

#### Scenario: Port-forward closed when the command ends

- **GIVEN** a command that opened one or more port-forwards
- **WHEN** the command ends
- **THEN** every port-forward it opened is closed

#### Scenario: Port-forward failure names the Service and error

- **GIVEN** a cluster with `exposure` PortForward
- **WHEN** a port-forward to a Service cannot be opened
- **THEN** the command fails with an error naming the namespace, the Service, the port and the Kubernetes error

#### Scenario: A command reuses one forward across its calls

- **GIVEN** a command that reads the same Service several times
- **WHEN** it opens that Service through the seam more than once
- **THEN** the seam reuses a single port-forward for that Service, and a long-running process (server, REPL, MCP) reopens one only when the prior forward has died

### Requirement: HTTP, JDBC and observability clients route through the seam

The HTTP observability readers (Mimir, Loki and Tempo), `GrafanaClient`, and the JDBC `sql` command SHALL obtain their endpoint from the seam rather than building a node address or reading a proxy port from a JVM property. HTTP clients SHALL set the per-client SOCKS proxy or no proxy from the endpoint through one shared helper, and the system SHALL NOT retain a process-wide HTTP client whose proxy route is a shared mutable selector.

#### Scenario: Observability readers use the seam

- **WHEN** a command reads Mimir, Loki or Tempo
- **THEN** it obtains the endpoint from the seam and builds its HTTP client with the per-client proxy that endpoint names

#### Scenario: JDBC sql uses the seam

- **WHEN** the `sql` command connects to a kit's JDBC endpoint
- **THEN** it obtains the endpoint from the seam, using the SOCKS TCP bridge for a SOCKS endpoint and a direct connection otherwise, and does not read the SOCKS port from a JVM property

#### Scenario: No stale process-wide tunnel port

- **GIVEN** the SOCKS tunnel has been replaced and now listens on a new port
- **WHEN** a command (for example the `down` save's Grafana annotation call) reads a cluster Service
- **THEN** its client uses the current tunnel port obtained through the seam, not a stale port held by a shared process-wide client

### Requirement: CQL reaches Cassandra per environment

On an AWS cluster the system SHALL reach Cassandra through the existing multi-node SOCKS session. On a cluster reached by port-forward the system SHALL use a single-endpoint mode: it forwards one Cassandra StatefulSet pod on a fixed local port, uses that forward as the sole contact point, marks every other node IGNORED through a node-distance evaluator, disables the driver token map, and sets the local datacenter.

#### Scenario: AWS CQL unchanged

- **GIVEN** an AWS cluster with `exposure` Socks
- **WHEN** a CQL command runs
- **THEN** it connects to the cluster's nodes through the SOCKS session exactly as before this change

#### Scenario: Single-endpoint CQL on a port-forwarded cluster

- **GIVEN** a cluster with `exposure` PortForward
- **WHEN** a CQL command runs
- **THEN** the driver connects only to the single forwarded Cassandra pod on its fixed local port, ignores every discovered peer, runs with the token map disabled, and has the local datacenter set

#### Scenario: CQL forward restarts on the same port

- **GIVEN** a running single-endpoint CQL session whose port-forward has died
- **WHEN** the forward is restarted
- **THEN** it is restarted on the same local port so the driver reconnects without reconfiguration

### Requirement: Status prints a kit endpoint reachable from the operator's machine

When `status` prints a kit or Service endpoint, the address SHALL work from the operator's machine on each environment. For a port-forward environment, where a live forward cannot outlive the command, `status` SHALL print the Service DNS name together with a ready-to-run `kubectl port-forward` command the operator can run.

#### Scenario: Status endpoint works on Socks and Direct

- **GIVEN** a cluster with `exposure` Socks or Direct
- **WHEN** `status` prints a kit endpoint
- **THEN** the printed address is reachable from the operator's machine on that environment

#### Scenario: Status prints a port-forward hint on a port-forward cluster

- **GIVEN** a cluster with `exposure` PortForward
- **WHEN** `status` prints a kit endpoint
- **THEN** it prints the Service DNS name and a ready-to-run `bin/kubectl port-forward` command naming the namespace, Service and port

### Requirement: Grafana exposed as a cluster Service

The system SHALL expose Grafana as a `ClusterIP` Service so the seam can forward to a Service rather than a pod.

#### Scenario: Grafana has a ClusterIP Service

- **WHEN** the observability stack is deployed
- **THEN** a `ClusterIP` Service for Grafana on its HTTP port exists, alongside the existing Mimir, Loki, Tempo and Pyroscope Services
