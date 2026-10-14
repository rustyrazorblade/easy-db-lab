## ADDED Requirements

### Requirement: observability export takes exactly one selector and a new path

The CLI SHALL provide `observability export <path>` with exactly one kind of selector:

- `--tenant <t>`: a file copy of the tenant's store;
- `--cluster <name-id>`, repeatable: an export by query of the selected clusters;
- `--experiment <name>`: an export by query of every cluster labelled with that experiment in one tenant.  `--tenant` SHALL name the experiment's tenant; with no `--tenant`, the tenant SHALL be the workspace's, or `default` outside a workspace, and the command SHALL say which tenant it used and why.

No selector, or more than one kind of selector, SHALL fail and name the three choices.  A missing path SHALL fail.  A path that already exists SHALL fail and SHALL NOT be overwritten.  The command SHALL work outside a cluster workspace.

#### Scenario: No selector is refused
- **WHEN** a user runs `observability export out.zip` with no selector
- **THEN** the command fails and names `--tenant`, `--cluster` and `--experiment`

#### Scenario: Two kinds of selector are refused
- **WHEN** a user runs `observability export out.zip --cluster x --experiment e`
- **THEN** the command fails and names `--tenant`, `--cluster` and `--experiment`

#### Scenario: A missing path is refused
- **WHEN** a user runs `observability export --tenant acme` with no path
- **THEN** the command fails and names the path argument

#### Scenario: An existing path is not overwritten
- **WHEN** the path already exists
- **THEN** the command fails and the existing file is unchanged

#### Scenario: The experiment's tenant defaults and is reported
- **WHEN** a user runs `observability export out.zip --experiment e` with no `--tenant` outside a workspace
- **THEN** the export uses tenant `default` and its output says so
- **AND** inside a workspace it uses the workspace's tenant and says so

### Requirement: A tenant export copies the tenant's store consistently

`--tenant <t>` SHALL copy, with no docker, the tenant's directories under `mimir/` and `tempo/`, Loki's chunks of the tenant and the tenant's index under `loki/index/`, the tenant's annotation backups under `grafana/annotations/<t>/`, and every report under `reports/<t>/`.  The copy SHALL stay consistent while the account compactor runs: it SHALL copy Loki's index table by table and redo a table that is replaced during the copy; it SHALL copy every Tempo block that has a `meta.json` and no compacted marker, and write the zip's own Tempo tenant index from the copied blocks; it SHALL read Mimir's bucket index once, copy the blocks with no deletion mark, and write the zip's own bucket index from the copied blocks; and it SHALL copy immutable objects last.  Before it copies, it SHALL sum the listing, print the size, and fail early when free disk space is short.  A tenant with no data SHALL fail and name the tenant and the prefixes it listed.

#### Scenario: The tenant's store is in the zip
- **WHEN** `observability export out.zip --tenant acme` runs
- **THEN** the zip holds acme's directories under `mimir/` and `tempo/`, and acme's Loki chunks and index

#### Scenario: A block merged during the copy is not lost
- **WHEN** the account compactor merges Mimir blocks of `acme` while the export runs
- **THEN** the zip's Mimir data opens with every sample the export's bucket index lists

#### Scenario: An empty tenant fails
- **WHEN** `--tenant ghost` runs and the store holds no data for `ghost`
- **THEN** the export fails and names `ghost` and the prefixes it looked under

#### Scenario: Short disk space fails early
- **WHEN** the tenant's data is larger than the free disk space at the path
- **THEN** the export fails before copying, naming the size and the free space

### Requirement: A query export holds only the selected clusters

`--cluster` and `--experiment` SHALL export by query, through a temporary read-only reader stack over the live store and a temporary writer stack with filesystem backends.  Logs SHALL be read from Loki and written to the writer's Loki with their structured metadata; traces SHALL be searched in time windows, fetched one by one, and replayed to the writer's Tempo; metrics SHALL be copied as the tenant's blocks that overlap the clusters' time range and rewritten to delete every series of another cluster, with the zip's own bucket index.  The metrics rewrite SHALL run with a filesystem store only, no AWS credentials and no network.  The zip SHALL hold the metrics, logs and traces that carry one of the selected clusters, and nothing of any other cluster.  The work directory SHALL be kept after the zip is written; the command SHALL print its location and size and SHALL NOT remove it.

`--experiment e` SHALL find its clusters as every cluster labelled `experiment=e` in the chosen tenant, and SHALL also find a cluster from before the label whose `<name>-<id>` is `e`.  `--cluster x` SHALL find the cluster's tenant: inside that cluster's own workspace from the state; with one tenant in the store, that tenant; with more, from one series query across all tenants.  Clusters of different tenants MAY be exported together.

#### Scenario: Only the selected clusters
- **WHEN** `observability export out.zip --cluster x --cluster y` runs
- **THEN** the zip holds the metrics, logs and traces that carry `cluster=x` or `cluster=y`
- **AND** it holds nothing of other clusters

#### Scenario: Every cluster of the experiment in one tenant
- **WHEN** `observability export out.zip --experiment e --tenant t` runs and tenants `t` and `u` each hold an experiment `e`
- **THEN** the zip holds the data of every cluster labelled `experiment=e` in tenant `t` only, and every report zip of `e` in `t`

#### Scenario: A pre-label cluster by experiment
- **WHEN** `--experiment lab-<id>` runs for a cluster from before the label
- **THEN** the zip holds that cluster's data and its existing documents

#### Scenario: A cluster's tenant is found across tenants
- **WHEN** `--cluster x` runs outside a workspace and the store holds tenants `default` and `acme`, and `x` is in `acme`
- **THEN** the export reads `x` from tenant `acme`

#### Scenario: A cluster that does not exist fails
- **WHEN** `--cluster nope-<id>` runs and no tenant holds that cluster
- **THEN** the export fails and names the cluster and the tenants it searched

#### Scenario: An experiment with no clusters fails
- **WHEN** `--experiment e --tenant t` runs and no cluster of `t` carries `experiment=e`
- **THEN** the export fails and names `e`, `t` and the query it ran

#### Scenario: Each signal is reported
- **WHEN** a query export finishes
- **THEN** its output reports what it exported for metrics, logs and traces
- **AND** a signal with zero results is reported as zero

#### Scenario: The work directory is kept
- **WHEN** a query export finishes
- **THEN** its work directory still exists and the output names its location and size

### Requirement: Every export is a complete, local, atomic zip

Every export SHALL hold the docker compose stack in offline mode with its configuration, the dashboards after the install-time pass for the export, the annotations of the clusters it covers, the raw data, and every report zip of each experiment it covers with its unpacked files and pointer.  It SHALL carry no images, and a README SHALL list the images the opening machine needs.  The zip SHALL be written only to the local path named; nothing SHALL be uploaded, and nothing in S3 SHALL be written or deleted.  The zip SHALL be written under a temporary name and moved to the path only when complete; on any failure no zip SHALL be left at the path, and the error SHALL name the signal and the step.  Every export's output and README SHALL say that profiles are not included and SHALL name #1016.

#### Scenario: The zip holds the stack, the dashboards and the annotations
- **WHEN** any export runs
- **THEN** the zip holds the docker compose stack, the dashboards, and the annotations of the clusters it covers

#### Scenario: Profiles are named as left out
- **WHEN** any export finishes
- **THEN** its output says that profiles are not included and names #1016

#### Scenario: A failure leaves no zip
- **WHEN** an export fails while copying traces
- **THEN** no zip is left at the path
- **AND** the error names the traces signal and the step

#### Scenario: Nothing in S3 changes
- **WHEN** an export runs
- **THEN** nothing in S3 is written or deleted, and nothing is uploaded

### Requirement: An export opens offline as the local stack

An unpacked export SHALL open with plain `docker compose up` on a machine with no network and no AWS credentials, when the stack's images are already on it.  It SHALL read from a filesystem backend over the zip's data, with no import step.  It SHALL show the metrics, logs and traces, the annotations, the dashboards, and the report.  The zip's data SHALL be mounted read-only into every backend.  Every store SHALL run with retention off, and no component SHALL compact, merge or delete data.

#### Scenario: The zip opens with no network
- **WHEN** the zip is opened on a machine with no network and no AWS credentials, and the stack's images are already on it
- **THEN** `docker compose up` shows the metrics, logs and traces, the annotations, the dashboards and the report, with no import step

#### Scenario: The opened data is never changed
- **WHEN** an opened export runs
- **THEN** every store has retention off
- **AND** no block is merged, marked compacted or deleted, and the files under the zip's data are unchanged
