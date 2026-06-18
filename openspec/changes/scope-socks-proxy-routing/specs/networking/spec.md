## ADDED Requirements

### Requirement: SOCKS Proxy Routes Only Cluster-Internal Traffic

The SOCKS5 proxy (REQ-NET-005) MUST route only cluster-internal traffic (the K3s API and other private cluster services). It MUST NOT capture traffic to public endpoints — in particular AWS SDK calls (S3, EC2, IAM, STS) MUST connect directly, out the host's normal network path, regardless of whether the proxy is running.

The system MUST NOT enable the proxy by setting the standard JVM-global `socksProxyHost`/`socksProxyPort` properties, because those route every socket the process opens through the tunnel. Instead, the active proxy port is published privately, and only the clients that need the tunnel (the Kubernetes client and the cluster HTTP client) configure the SOCKS proxy explicitly.

#### Scenario: AWS calls go direct while the proxy is running

- **GIVEN** a provisioned cluster with infrastructure UP and the SOCKS5 proxy active
- **WHEN** the CLI makes an AWS SDK call (e.g. S3 backup, EC2 describe, IAM policy update)
- **THEN** the call connects directly to the AWS endpoint and is not routed through the SSH tunnel.

#### Scenario: Cluster-internal traffic still uses the tunnel

- **GIVEN** the SOCKS5 proxy is active and Tailscale is not enabled
- **WHEN** the CLI reaches the K3s API or a private cluster service (e.g. VictoriaMetrics/VictoriaLogs on the control node)
- **THEN** that traffic is routed through the SOCKS proxy via explicit per-client configuration.

#### Scenario: Tailscale active means no proxy and all-direct routing

- **GIVEN** Tailscale is enabled for the cluster, so the SOCKS5 proxy is never started and the proxy port is not published
- **WHEN** the CLI reaches the K3s API, a private cluster service, or any AWS endpoint
- **THEN** every connection is direct — the K8s client and the cluster HTTP client both select no proxy, using Tailscale's private network for cluster traffic.

#### Scenario: State backup/restore works on a network that blocks the tunnel path

- **GIVEN** a network where the account S3 bucket is reachable directly from the operator's machine but the cluster tunnel cannot reach it
- **WHEN** the CLI backs up or restores cluster state to/from S3
- **THEN** the S3 traffic connects directly and succeeds, because it is never forced through the tunnel.
