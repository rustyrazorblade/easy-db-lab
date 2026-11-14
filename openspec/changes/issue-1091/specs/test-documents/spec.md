## MODIFIED Requirements

### Requirement: Grafana reads test documents through a read-only web server

The Grafana pod SHALL run two more containers, built with fabric8:

- an `aws-sigv4-proxy` container, bound to `127.0.0.1` on a fixed port, that signs requests to S3 with the default credential chain for the account bucket's region;
- an nginx front that holds Grafana's client port (3000), the container port the grafana ClusterIP Service targets. Grafana itself SHALL listen on `127.0.0.1` only, on a fixed internal port, behind the front.

The front SHALL send `GET` and `HEAD` requests for paths under `/reports/` to the proxy, and every other path to Grafana. Under `/reports/` it SHALL refuse every other method, SHALL prefix the account bucket itself, SHALL match the normalized path, and SHALL drop the query string. Toward Grafana it SHALL preserve the `Host` header, SHALL pass websocket upgrades through (Grafana Live), and SHALL NOT limit the request body size. No container in the pod SHALL have a separate documents port or a documents host port, and the documents URL the dashboards use SHALL be the relative path `/reports/<tenant>/<name>-<id>/...`, so the documents are same-origin with Grafana over whatever single connection reaches it (a `127.0.0.1` port-forward, or the SOCKS path on AWS).

The proxy's port and Grafana's internal port SHALL be constants and SHALL be listed in the port reference; neither SHALL be 8081, which the image renderer uses. The front's configuration SHALL be a resource rendered by `TemplateService` and SHALL be part of the configuration hash that rolls the Grafana pod. Grafana SHALL run with `[panels] disable_sanitize_html = true`, so a Text panel in HTML mode can hold the documents iframe.

#### Scenario: A document is served
- **WHEN** a browser requests `/reports/<tenant>/<name>-<id>/index.html` on Grafana's client port
- **THEN** it receives the index from the account bucket

#### Scenario: Documents share Grafana's single connection
- **GIVEN** the browser reaches Grafana only through a `127.0.0.1` port-forward
- **WHEN** a Text panel's documents iframe loads
- **THEN** the documents are served same-origin over that same connection, from a relative URL, with no host port and no node-IP URL

#### Scenario: A write is refused
- **WHEN** a client sends a `PUT`, `POST`, `DELETE` or `PATCH` request under `/reports/`
- **THEN** the front refuses it, and nothing reaches S3 or Grafana

#### Scenario: A path outside reports goes to Grafana, never to S3
- **WHEN** a client requests a path that is not under `/reports/`, such as `/mimir/` or `/reports/../mimir/`
- **THEN** the request reaches Grafana, and nothing reaches the proxy

#### Scenario: Grafana is reached through the front
- **WHEN** a client calls a Grafana API path, or opens the Grafana Live websocket, on Grafana's client port
- **THEN** Grafana receives the request with its method, body, `Host` header and upgrade intact

#### Scenario: The proxy is not reachable from the network
- **WHEN** another host on the VPC or the tailnet connects to the proxy's port on the control node
- **THEN** the connection fails

#### Scenario: The proxy signs for the bucket's region
- **WHEN** the account bucket is in a region other than the cluster's
- **THEN** the documents are still served

#### Scenario: A front configuration change rolls Grafana
- **WHEN** the front's configuration changes and `grafana update-config` runs
- **THEN** the Grafana pod is replaced
