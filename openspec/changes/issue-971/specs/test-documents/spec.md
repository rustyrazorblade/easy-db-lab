## ADDED Requirements

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

- an `aws-sigv4-proxy` container, bound to `127.0.0.1` on a fixed port, that signs requests to S3 with the instance role for the account bucket's region;
- a read-only web server on a fixed host port, reachable from the browser the same way as Grafana, that forwards only `GET` requests for paths under `reports/` to the proxy.  It SHALL prefix the account bucket itself, SHALL match the normalized path, and SHALL drop the query string.

Both ports SHALL be constants and SHALL be listed in the port reference; neither SHALL be 8081, which the image renderer uses.  The web server's configuration SHALL be part of the configuration hash that rolls the Grafana pod.  Grafana SHALL run with `[security] disable_sanitize_html = true`, so a Text panel in HTML mode can hold the documents iframe.

#### Scenario: A document is served

- **WHEN** a browser requests `/reports/<tenant>/<name>-<id>/index.html` from the documents web server
- **THEN** it receives the index from the account bucket

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

### Requirement: A doc_tenant variable selects the documents' tenant folder

Every dashboard that shows documents SHALL declare one `doc_tenant` custom variable.  The installer SHALL fill its options from the tenant list and SHALL default it to the cluster's own tenant.  The iframe path SHALL be `reports/${doc_tenant}/<cluster>/index.html`.  The operator sets `doc_tenant` together with the pickers; documents of two tenants are not shown on one dashboard.

#### Scenario: doc_tenant defaults to the home tenant

- **WHEN** an operator opens an installed Tests dashboard on a cluster in tenant `acme`
- **THEN** `doc_tenant` shows `acme`

#### Scenario: doc_tenant lists every tenant

- **WHEN** the account holds tenants `default` and `acme`
- **THEN** `doc_tenant` offers `default` and `acme`
