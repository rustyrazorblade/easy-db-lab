## ADDED Requirements

### Requirement: local create writes a docker compose stack into a named directory

The CLI SHALL provide `observability local create <dir> [--tenant <home>]`.  The directory SHALL be required and SHALL have no default.  The command SHALL write a docker compose stack into `<dir>`, with `compose.yml` as its base file: Mimir with its memcached, Loki, Tempo, Grafana, the documents view, and, in live mode, a read gate and a signer.  Pyroscope SHALL NOT be part of the stack until #1016.  Every file SHALL come from resources packaged in the distribution; the command SHALL NOT read a source checkout.  `docker compose up` in the directory SHALL bring up a working stack with no edits.  The command SHALL record the files it owns in a manifest in the directory, with the tool version, the mode, the home tenant, the tenant list, a checksum per owned file, and the Grafana port.  A missing or empty directory SHALL be created.  A directory that is not empty and holds no manifest SHALL be refused before anything is written.  The home tenant SHALL be `--tenant`, default `default`.  The command SHALL work outside a cluster workspace.

#### Scenario: The directory is required
- **WHEN** a user runs `observability local create` with no directory
- **THEN** the command fails and names the missing directory argument

#### Scenario: A new directory gets a working stack
- **WHEN** a user runs `observability local create ~/lab-local` and then `docker compose up` in `~/lab-local`
- **THEN** the stack comes up and Grafana answers with no edit to any file

#### Scenario: A foreign directory is refused
- **WHEN** the directory is not empty and holds no manifest of the tool
- **THEN** the command fails, names the directory, and writes and overwrites nothing

#### Scenario: A Homebrew install builds the whole stack
- **WHEN** the command runs from an install with no source checkout
- **THEN** the stack it writes is complete

### Requirement: local create re-runs in place without touching data

A re-run of `local create` on a directory it generated SHALL rewrite only the files it owns, each atomically.  An owned file the user edited SHALL be overwritten, and the command SHALL report it.  The data directories and `compose.override.yml` SHALL never be opened, rewritten or removed.  A re-run SHALL list the tenant directories again, rewrite the datasource file, and ask a running Grafana to reload its datasources, so a new tenant appears.

#### Scenario: Data and the override stay byte-identical
- **WHEN** `local create` re-runs on a directory it generated that holds data and a `compose.override.yml`
- **THEN** the data and `compose.override.yml` are byte-identical to before
- **AND** only the files in the manifest are rewritten

#### Scenario: An edited owned file is overwritten and reported
- **WHEN** the user edited an owned file and `local create` re-runs
- **THEN** the file is rewritten and the output names it

#### Scenario: A new tenant appears on a re-run
- **WHEN** a tenant first writes data after the stack was created and `local create` re-runs
- **THEN** local Grafana has datasources for that tenant without a restart

### Requirement: The local stack's images match the cluster's pins

Every image in the generated stack SHALL be the image the cluster runs for that component, taken from the same pins: Mimir, its memcached, Loki, Tempo, Grafana, the documents web server and the `aws-sigv4-proxy`.  An image the cluster does not run, such as the read gate's web server or the export's thanos, SHALL be pinned to a released version.  No image SHALL use `latest`.  A unit test SHALL fail when an image of the generated stack differs from its registered pin.

#### Scenario: Local tags match the cluster's pins
- **WHEN** the generated stack's images are compared with the cluster's pins
- **THEN** each one matches

#### Scenario: Drift fails the test
- **WHEN** a cluster pin changes and the local stack would use another tag
- **THEN** the unit test fails and names the image

### Requirement: The live local stack reads the shared store directly

In live mode, the stack SHALL read the live shared store in S3 with the owner's AWS profile.  It SHALL need no running cluster and no import step.  It SHALL show the metrics, logs and traces of every tenant, including clusters that are torn down.  The signer SHALL read the owner's `~/.aws` read-only, with the profile the tool is configured with.  Mimir SHALL run memcached for its caches, as on a cluster.  Tempo SHALL list the bucket when a tenant index is stale, so the last blocks of a cluster just torn down appear.

#### Scenario: Torn-down clusters show with no cluster up
- **WHEN** the live stack runs and no cluster is up
- **THEN** Grafana shows the metrics, logs and traces of torn-down clusters from the shared store

#### Scenario: The local Mimir caches as on a cluster
- **WHEN** the live stack runs
- **THEN** Mimir's index, chunks, metadata and results caches use the stack's memcached

#### Scenario: The newest traces appear before the index is rebuilt
- **WHEN** a cluster was just torn down and Tempo's tenant index does not list its last blocks yet
- **THEN** local Tempo finds those blocks by listing the bucket

### Requirement: The live local stack writes nothing to the bucket

Every backend of the live stack SHALL reach S3 only through a read gate that passes `GET` and `HEAD` requests and refuses every other method.  The backends SHALL run read-only components only, and no compactor SHALL run in the stack.  An integration test SHALL list the bucket (key and ETag), run queries for metrics, logs and traces through the stack, list the bucket again, and fail if anything was written, rewritten or deleted.

#### Scenario: The bucket listing is unchanged
- **WHEN** the live stack runs and serves metrics, logs and traces queries
- **THEN** the bucket listing, keys and ETags, is the same before and after

#### Scenario: A write is refused by the gate
- **WHEN** a backend in the live stack sends a `PUT`, `POST` or `DELETE` to S3, such as Tempo writing a tenant index
- **THEN** the gate refuses it and nothing reaches S3

#### Scenario: No compactor runs
- **WHEN** the live stack runs
- **THEN** no Mimir, Loki or Tempo compactor process runs

### Requirement: A store the stack cannot read fails loudly

When AWS credentials are missing, expired, or cannot read the account bucket, the stack SHALL say so and SHALL NOT look like an empty store.  `local create` SHALL check that the profile can read the bucket and list `mimir/`, and SHALL fail with the profile, the AWS error code and the login command.  A one-shot check SHALL run before every backend starts and SHALL exit non-zero with the same message.  The signer SHALL report unhealthy once its credentials can no longer read the bucket.

#### Scenario: Missing credentials fail create
- **WHEN** `local create` runs with a profile that cannot read the bucket
- **THEN** it fails and names the profile, the AWS error code, and `aws sso login --profile <profile>`

#### Scenario: Expired credentials stop the stack from starting
- **WHEN** `docker compose up` runs after the profile's credentials expired
- **THEN** the store check exits non-zero with a message that it cannot read the account bucket with that profile
- **AND** no backend starts

#### Scenario: Credentials that expire while the stack runs show as unhealthy
- **WHEN** the credentials expire while the stack runs
- **THEN** `docker compose ps` shows the signer as unhealthy

### Requirement: Local Grafana has the same datasources and pickers as a cluster

Local Grafana's datasources SHALL be built by the same code that builds a cluster's, from the tenant directories under `mimir/` and the home tenant, with the same UIDs and types; only the URLs SHALL differ.  It SHALL have the stable `mimir`, `loki` and `tempo` datasources on the home tenant, one `<signal>-<tenant>` datasource per tenant, and one `<signal>--all` datasource for metrics, logs and traces.  The dashboards SHALL be the cluster's dashboards after the install-time pass for the local stack.  The documents view SHALL serve the reports from the store through the read gate.

#### Scenario: Same UIDs as a cluster
- **WHEN** the store holds tenants `default` and `acme`
- **THEN** local Grafana has the same datasource UIDs as a cluster's Grafana, including `mimir-acme`, `mimir-default` and `mimir--all`, and the same for logs and traces

#### Scenario: Clusters of different tenants on one panel
- **WHEN** the all-tenants datasource is selected on a comparison dashboard
- **THEN** clusters of different tenants show on one panel

#### Scenario: The report shows locally
- **WHEN** an experiment has a report and an operator opens its Experiment dashboard in the local stack
- **THEN** the report shows in the documents panel

### Requirement: local dashboards refreshes only the dashboards of a running stack

The CLI SHALL provide `observability local dashboards <dir> [--from <dir>]`.  It SHALL read the stack's manifest and check that its Grafana answers; if Grafana is not running it SHALL fail and change nothing.  It SHALL write the dashboard tree after the install-time pass into a staging directory beside the provisioned tree, rename it into place, and ask Grafana to reload its dashboard provisioning.  It SHALL NOT call docker, so no container is recreated, and no data is lost.  `--from` SHALL name any directory of `<folder>/<file>.json` dashboards, used with no rebuild.  With no `--from` it SHALL use the packaged core and kit dashboards and SHALL say so.

#### Scenario: New dashboards show with no restart
- **WHEN** `observability local dashboards ~/lab-local` runs against a running stack
- **THEN** Grafana shows the new dashboards with no restart
- **AND** no storage container is recreated

#### Scenario: Dashboards from a directory
- **WHEN** `--from ~/work/dashboards` is given
- **THEN** the dashboards come from that directory with no rebuild

#### Scenario: Packaged dashboards by default
- **WHEN** `--from` is omitted
- **THEN** the packaged dashboards are used and the command says so

#### Scenario: A stopped stack is refused
- **WHEN** the stack is not running
- **THEN** the command fails and changes nothing
