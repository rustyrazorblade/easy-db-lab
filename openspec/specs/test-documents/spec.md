# test-documents Specification

## Purpose
TBD - created by archiving change issue-971. Update Purpose after archive.
## Requirements
### Requirement: report upload stores documents in the test's folder

A command `report upload FILE...`, run from a cluster workspace, SHALL upload one or more files to the cluster's document folder, `reports/<tenant>/<name>-<id>/`, in the account bucket.  Each file SHALL keep its own name.  The command SHALL use the operator's own AWS credentials and SHALL need only the workspace's cluster state, so it works before and after `down`.  Uploading a file with the name of an existing document SHALL replace it.  A test MAY have any number of documents with any names that the name rule allows; no name or count SHALL be fixed in the code or in a dashboard.  On success the command SHALL emit one typed event that lists each document's name and S3 URI.

#### Scenario: Files are stored in the test's folder

- **WHEN** an operator runs `report upload results.md notes.md` in the workspace of cluster `lab-<id>` in tenant `acme`
- **THEN** `reports/acme/lab-<id>/results.md` and `reports/acme/lab-<id>/notes.md` exist in the account bucket

#### Scenario: Upload after down

- **WHEN** the operator runs `report upload` after `down`
- **THEN** the upload succeeds

#### Scenario: Same name replaces the document

- **WHEN** an operator uploads a file with the name of an existing document
- **THEN** the new file replaces the old one

### Requirement: report upload accepts only markdown files with safe names

`report upload` SHALL accept only `.md` files whose names match `[A-Za-z0-9._-]+`.  It SHALL reject `index.md`, any other file type, any other name, a missing file and a directory.  It SHALL name each file it rejects and SHALL upload nothing when it rejects any file.

#### Scenario: A file that is not markdown is rejected

- **WHEN** an operator runs `report upload graph.png`
- **THEN** the command fails, names `graph.png`, and uploads nothing

#### Scenario: A name outside the character set is rejected

- **WHEN** an operator runs `report upload "my notes.md"`
- **THEN** the command fails, names `my notes.md`, and uploads nothing

#### Scenario: index.md is rejected

- **WHEN** an operator runs `report upload index.md`
- **THEN** the command fails and names `index.md`

### Requirement: report upload renders each document and rebuilds the test's index

`report upload` SHALL convert each markdown file to HTML and store both, as `<stem>.md` and `<stem>.html`, in the test's folder.  It SHALL then rewrite one `index.html` for the test from every `.md` document in the folder, each rendered under its own heading, which is its name, in name order.  Every generated HTML file SHALL declare `<meta charset="utf-8">` and SHALL be uploaded from a local `.html` file.

#### Scenario: Each document gets an HTML copy

- **WHEN** an operator uploads `results.md`
- **THEN** the test's folder holds `results.md` and `results.html`

#### Scenario: The index holds every document

- **WHEN** a test's folder holds `a.md` and `b.md` and the operator uploads `c.md`
- **THEN** the test's `index.html` holds `a`, `b` and `c`, each rendered in its own section headed with its name

#### Scenario: A replaced document is replaced in the index

- **WHEN** an operator uploads a new `results.md` over an existing one
- **THEN** the index shows the new content of `results.md` once

### Requirement: up writes an empty index for the test

`up` SHALL rebuild the test's `index.html` with the operator's credentials, in the same way `report upload` does.  When the test has no documents, the index SHALL say "No documents yet".

#### Scenario: A new cluster has an empty index

- **WHEN** `up` runs on a new cluster
- **THEN** `reports/<tenant>/<name>-<id>/index.html` exists and says "No documents yet"

#### Scenario: A re-run of up keeps the documents in the index

- **WHEN** `up` runs again on a cluster whose folder holds `results.md`
- **THEN** the test's `index.html` still holds `results`

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

#### Scenario: The proxy is reachable only inside the Grafana pod
- **GIVEN** the signing proxy binds `127.0.0.1:3081` in the Grafana pod, publishes no host port, and no Service targets it
- **WHEN** anything outside that pod (another pod, a node, a host on the VPC or the tailnet) connects to the pod's IP on 3081, or to any node on 3081
- **THEN** the connection fails: only the front, in the same pod, reaches the proxy over loopback

#### Scenario: The proxy signs for the bucket's region
- **WHEN** the account bucket is in a region other than the cluster's
- **THEN** the documents are still served

#### Scenario: A front configuration change rolls Grafana
- **WHEN** the front's configuration changes and `grafana update-config` runs
- **THEN** the Grafana pod is replaced

### Requirement: A doc_tenant variable selects the documents' tenant folder

Every dashboard that shows documents SHALL declare one `doc_tenant` custom variable.  The installer SHALL fill its options from the tenant list and SHALL default it to the cluster's own tenant.  The iframe path SHALL be `reports/${doc_tenant}/<cluster>/index.html`.  The operator sets `doc_tenant` together with the pickers; documents of two tenants are not shown on one dashboard.

#### Scenario: doc_tenant defaults to the home tenant

- **WHEN** an operator opens an installed Tests dashboard on a cluster in tenant `acme`
- **THEN** `doc_tenant` shows `acme`

#### Scenario: doc_tenant lists every tenant

- **WHEN** the account holds tenants `default` and `acme`
- **THEN** `doc_tenant` offers `default` and `acme`

