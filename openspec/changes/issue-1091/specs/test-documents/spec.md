## MODIFIED Requirements

### Requirement: Grafana reads test documents through a read-only web server

The Grafana pod SHALL run two more containers, built with fabric8:

- an `aws-sigv4-proxy` container, bound to `127.0.0.1` on a fixed port, that signs requests to S3 with the default credential chain for the account bucket's region;
- a read-only web server that forwards only `GET` requests for paths under `reports/` to the proxy. It SHALL be exposed as a second port on the grafana ClusterIP Service, reachable same-origin through the one connection that reaches Grafana (a `127.0.0.1` port-forward, or the SOCKS path on AWS), SHALL NOT use a host port, and the documents URL the dashboards use SHALL be same-origin and relative. It SHALL prefix the account bucket itself, SHALL match the normalized path, and SHALL drop the query string.

Both container ports SHALL be constants and SHALL be listed in the port reference; neither SHALL be 8081, which the image renderer uses. The web server's configuration SHALL be part of the configuration hash that rolls the Grafana pod. Grafana SHALL run with `[panels] disable_sanitize_html = true`, so a Text panel in HTML mode can hold the documents iframe.

#### Scenario: A document is served
- **WHEN** a browser requests `/reports/<tenant>/<name>-<id>/index.html` through the grafana Service's documents port
- **THEN** it receives the index from the account bucket

#### Scenario: Documents share Grafana's single connection
- **GIVEN** the browser reaches Grafana only through a `127.0.0.1` port-forward
- **WHEN** a Text panel's documents iframe loads
- **THEN** the documents are served same-origin over that same connection, with no host port and no node-IP URL

#### Scenario: A write is refused
- **WHEN** a client sends a `PUT`, `POST` or `DELETE` request to the documents web server
- **THEN** the web server refuses it and nothing reaches S3

#### Scenario: A path outside reports is refused
- **WHEN** a client requests a path that is not under `reports/`, such as `/mimir/` or `/reports/../mimir/`
- **THEN** the web server refuses it

#### Scenario: The proxy is not reachable from the network
- **WHEN** another host on the VPC or the tailnet connects to the proxy's port on the control node
- **THEN** the connection fails

#### Scenario: The proxy signs for the bucket's region
- **WHEN** the account bucket is in a region other than the cluster's
- **THEN** the documents web server still serves the documents

#### Scenario: A web server configuration change rolls Grafana
- **WHEN** the documents web server configuration changes and `grafana update-config` runs
- **THEN** the Grafana pod is replaced
