# Local Observability Stack Spec

## ADDED Requirements

### Requirement: `local` command group

The CLI SHALL expose a `local` top-level parent command, the counterpart to the existing `aws`
group: `aws <thing>` operates on AWS, `local <thing>` operates on the user's machine. The group
SHALL carry the subcommands `scaffold`, `import`, `ls`, `rm`, and `dashboards`.

The destination directory SHALL be a required positional argument on every subcommand. The CLI
SHALL NOT choose a location itself and SHALL NOT carry a default.

No command in the group SHALL carry `@RequiresProxy`, because none of them reaches a cluster. Each
command's KDoc SHALL state this, because the existing `metrics import` and `logs import` commands
are live-cluster streams and the name similarity will otherwise mislead.

Only `local import` reads `state.json`. `scaffold`, `ls`, `rm` and `dashboards` SHALL run from any
working directory, with no cluster workspace present.

#### Scenario: A subcommand invoked with no directory fails

- **WHEN** the user runs any `local` subcommand with no directory argument
- **THEN** the CLI fails naming the missing argument
- **AND** it does not pick a location itself and creates nothing

#### Scenario: Four of the five commands need no cluster workspace

- **GIVEN** a working directory that holds no `state.json`
- **WHEN** the user runs `local scaffold`, `local ls`, `local rm` or `local dashboards` against a scaffold directory
- **THEN** the command runs and no cluster-state error is raised

#### Scenario: No `local` command reaches a cluster

- **WHEN** the `local` command classes are inspected
- **THEN** none of them carries `@RequiresProxy`
- **AND** each command's KDoc states that it reaches no cluster

### Requirement: Scaffold generation

`local scaffold <dir>` SHALL generate a self-contained local observability environment into `<dir>`
— `docker-compose.yml`, `.env`, service configuration for VictoriaMetrics, VictoriaLogs, Tempo and
Pyroscope, Grafana datasource and dashboard provisioning, a `.gitignore`, a `README.md`, and
`scaffold.json` — such that `docker compose up` brings the stack up with no further edits.

A separate extraction step SHALL write dashboard JSON into the scaffold's top-level `dashboards/`
directory. It SHALL NOT co-mingle with configuration generation.

The scaffold SHALL be generated entirely from resources packaged into the distribution. No part of
the scaffold path SHALL read a source checkout, a working tree, or any path inside the repository.

The generated files SHALL NOT collide with the repository's own unrelated top-level
`docker-compose.yml` and `otel-collector-config.yaml`.

#### Scenario: A generated scaffold starts with no edits

- **WHEN** the user runs `local scaffold ./runs`
- **THEN** `./runs` is created holding `docker-compose.yml`, service configuration, Grafana datasource provisioning and dashboard JSON
- **AND** `docker compose up` in `./runs` brings the stack up with no further edits

#### Scenario: Generation succeeds with no source checkout

- **GIVEN** a machine that installed the tool from a package and has no repository clone
- **WHEN** the user runs `local scaffold ./runs`
- **THEN** the scaffold is generated complete from packaged resources
- **AND** nothing in the scaffold path reads a working tree

#### Scenario: The scaffold directory is gitignored

- **GIVEN** the scaffold directory is created inside a git-tracked workspace
- **WHEN** generation completes
- **THEN** the scaffold carries a `.gitignore` that keeps its contents out of the enclosing repository

### Requirement: Scaffold update in place is idempotent

Re-running `local scaffold <dir>` against a directory the tool previously generated SHALL update it
in place. It SHALL rewrite only the paths `scaffold.json` records as tool-owned, and SHALL leave
`data/`, `imports/`, `staging/` and `compose.override.yml` byte-identical.

Running against a directory the tool did not generate and which is not empty SHALL fail, naming the
directory, and SHALL overwrite nothing.

A regenerate that picks up a newer image pin or a new datasource SHALL leave an aggregate already
restored into that directory queryable.

#### Scenario: Regeneration leaves data and user files untouched

- **GIVEN** a scaffold generated earlier that holds restored data, import records and a user-written `compose.override.yml`
- **WHEN** the user runs `local scaffold ./runs` again
- **THEN** every path listed in `scaffold.json` is rewritten
- **AND** `data/`, `imports/` and `compose.override.yml` are byte-identical to before

#### Scenario: A foreign non-empty directory is refused

- **GIVEN** a non-empty directory the tool did not generate, with no `scaffold.json`
- **WHEN** the user runs `local scaffold` against it
- **THEN** the command fails naming the directory
- **AND** no file in it is created, rewritten or removed

#### Scenario: A restored aggregate survives a newer scaffold

- **GIVEN** a scaffold holding a restored aggregate
- **WHEN** a regenerate applies a newer image pin or adds a datasource
- **THEN** the restored aggregate is still queryable from the regenerated stack

### Requirement: `scaffold.json` records what the tool owns

The scaffold SHALL carry a manifest named `scaffold.json`, named after the command that writes it.
It SHALL record the scaffold version, the image pins, and the list of paths the tool owns.

Every path in the scaffold SHALL fall into exactly one of four classes, and the manifest SHALL be
the authority on which:

- **Tool-owned, rewritten on every generate run** — `docker-compose.yml`, `.env`,
  `config/victoriametrics/`, `config/victorialogs/`, `config/tempo/`, `config/pyroscope/`,
  `config/grafana/provisioning/datasources/`, `config/grafana/provisioning/dashboards/`,
  `.gitignore`, `README.md`, `scaffold.json` itself.
- **Written by the separate extraction step** — `dashboards/`.
- **User-owned, never rewritten** — `compose.override.yml`.
- **Data, never written by a generate run** — `data/victoriametrics/`, `data/victorialogs/`,
  `data/tempo/`, `data/pyroscope/`, `imports/`, `staging/`.

The data directories SHALL be bind mounts under the scaffold, so the aggregate is a directory the
user can copy and inspect.

#### Scenario: The manifest names the owned paths

- **WHEN** a scaffold is generated
- **THEN** `scaffold.json` records the scaffold version, the image pins, and every tool-owned path
- **AND** it lists neither `compose.override.yml` nor any path under `data/`, `imports/` or `staging/`

#### Scenario: The aggregate is a directory on disk

- **GIVEN** a scaffold with data imported into it
- **WHEN** the user inspects the scaffold directory
- **THEN** the stores' data is present under `data/` as bind mounts that can be copied and inspected

### Requirement: Local Grafana datasources are identical to the cluster's

The scaffold's Grafana datasource provisioning SHALL be generated from the same
`GrafanaDatasourceConfig.create()` the cluster uses, substituting only the URLs, so UID drift
between the local stack and the cluster is impossible.

Grafana SHALL expose exactly four datasources: `VictoriaMetrics` (type `prometheus`), `victorialogs`
(type `victoriametrics-logs-datasource`), `tempo`, and `pyroscope`. The VictoriaLogs and Pyroscope
datasource plugins SHALL be installed in the local Grafana image.

A Tempo container SHALL be included so that `dashboards/tempo.json`'s datasource resolves. It holds
no traces until trace backup and import land separately.

#### Scenario: The datasource list matches the cluster's

- **WHEN** the local stack is up and Grafana has loaded its provisioning
- **THEN** the datasource list contains exactly `VictoriaMetrics` (type `prometheus`), `victorialogs` (type `victoriametrics-logs-datasource`), `tempo` and `pyroscope`
- **AND** each UID is the same value the cluster's Grafana uses

#### Scenario: A dashboard's datasource variable resolves

- **WHEN** a dashboard whose `datasource` variable defaults to `VictoriaMetrics` is opened in the local Grafana
- **THEN** the variable resolves
- **AND** no panel reports "datasource not found"

#### Scenario: The VictoriaLogs datasource plugin is present

- **WHEN** the local Grafana starts
- **THEN** the VictoriaLogs datasource plugin is installed and the `victorialogs` datasource loads

### Requirement: Local image tags are pinned to the cluster's versions

Every image the scaffold pins SHALL be the version the cluster's manifest builders pin. A `vmbackup`
artifact restored into a mismatched VictoriaMetrics is not supported, so a drift between the two is
a defect rather than a tolerance.

The pins SHALL live in `.env`, so a host port clash or a deliberate version change is a one-line
edit.

#### Scenario: Local pins match the manifest builders'

- **WHEN** the scaffold's image tags are compared to the versions pinned in the cluster's manifest builders
- **THEN** every tag matches

#### Scenario: Ports and pins are editable in one file

- **WHEN** the user needs to resolve a host port clash
- **THEN** the port is changed by editing `.env` alone, with no edit to `docker-compose.yml`

### Requirement: The scaffold carries exactly one copy of each dashboard

The dashboard extraction step SHALL write both the core dashboards and every kit dashboard into the
scaffold's `dashboards/` directory, and SHALL write exactly one copy of each dashboard.

Where a dashboard exists both as a stale top-level copy and as a kit copy, the kit copy is
authoritative and is the one extracted.

#### Scenario: Core and kit dashboards are both extracted

- **WHEN** a scaffold is generated
- **THEN** the scaffold's `dashboards/` directory holds the core dashboards and every kit dashboard

#### Scenario: One copy of each ClickHouse dashboard

- **WHEN** a scaffold is generated
- **THEN** it contains exactly one copy of each ClickHouse dashboard, taken from the ClickHouse kit

### Requirement: Import addresses its source from the cluster workspace

`local import <dir>` SHALL read its source from `state.json` in the current working directory,
exactly as every other command in this tool works. The destination directory SHALL be the only
required argument. There SHALL be no `--cluster` flag in the primary form.

`--timestamp` SHALL be an optional override used to reach a snapshot older than the latest. It is
never part of addressing.

An explicit `s3://bucket/prefix/timestamp` URI SHALL remain available as the escape hatch for when
`state.json` is gone. Because all three tiers share one S3 prefix tree in the account bucket, one
URI addresses a run and no per-tier URI flag is needed.

A failure SHALL name what it looked at. A missing prefix, a missing credential, and a credential
without bucket access SHALL each produce a message that says so.

#### Scenario: Import takes only a destination

- **GIVEN** a cluster workspace whose `state.json` names the cluster and its account bucket
- **WHEN** the user runs `local import ./runs`
- **THEN** the latest backup of that cluster is imported
- **AND** no `--cluster` argument was required

#### Scenario: `--timestamp` reaches an older snapshot

- **GIVEN** a cluster with more than one snapshot in S3
- **WHEN** the user runs `local import ./runs --timestamp <older>`
- **THEN** the named snapshot is imported instead of the latest

#### Scenario: An explicit URI works without a workspace

- **GIVEN** the workspace `state.json` is gone
- **WHEN** the user runs `local import ./runs s3://bucket/prefix/timestamp`
- **THEN** the import runs against that URI

#### Scenario: A missing prefix names the URI it looked at

- **WHEN** the timestamp or prefix given is absent from S3
- **THEN** the command fails naming the S3 URI it looked at
- **AND** it neither prints a stack trace nor reports an empty success

#### Scenario: A credential problem says so

- **WHEN** AWS credentials are missing, or present but without access to the bucket
- **THEN** the failure states that credentials are missing or lack access
- **AND** it does not present as an empty backup

### Requirement: A bare import covers all three tiers

A bare `local import` SHALL import metrics, logs and profiles together. There SHALL be no per-tier
flag.

Metrics SHALL be merged into the local VictoriaMetrics store so that a series present in the source
cluster returns data over the backed-up range. Logs SHALL be restored so that VictoriaLogs serves
the restored partitions.

On a first import, profiles accumulate rather than snapshot: that S3 prefix is written continuously,
so blocks are added and deduplicated on their leaf ULIDs.

#### Scenario: Metrics are queryable after import

- **GIVEN** a metrics backup timestamp that `metrics ls` reports
- **WHEN** the user imports it
- **THEN** it is merged into the local VictoriaMetrics store
- **AND** a query for a series present in the source cluster returns data over the backed-up range

#### Scenario: Logs are queryable after import

- **WHEN** a logs backup is imported
- **THEN** VictoriaLogs serves the restored partitions
- **AND** `dashboards/log-investigation.json` returns rows

#### Scenario: Profiles are deduplicated on their leaf ULIDs

- **WHEN** profiles are imported
- **THEN** blocks are keyed on their leaf ULIDs so no block is added twice
- **AND** the command reports how many blocks it added

#### Scenario: Adding no profile blocks is stated explicitly

- **WHEN** a profile import adds no new blocks
- **THEN** the command says so explicitly
- **AND** it does not report a bare success

### Requirement: A second snapshot of the same cluster is refused

The archive SHALL key a snapshot on `(clusterLabel, tier)`, independent of timestamp. When a metrics
or logs snapshot for that cluster is already recorded, a bare import SHALL refuse the whole command.
It SHALL name the recorded snapshot and its time range, and SHALL state that `--replace` replaces
it. It SHALL NOT import any tier partially.

`vmbackup` output is a full snapshot, not an incremental one, so importing two snapshots of one
cluster instance double-counts every overlapping sample: each carries its own import label, so they
land as two distinct series covering the same period under the same cluster name, and any sum across
them is wrong.

This is stronger than duplicate detection, which only catches importing the *same* backup twice.

#### Scenario: A second snapshot of a recorded cluster is refused

- **GIVEN** the archive already holds a metrics snapshot for cluster `alpha`
- **WHEN** the user runs a bare `local import ./runs` for a different snapshot of `alpha`
- **THEN** the command refuses, naming the recorded snapshot and its time range
- **AND** it states that `--replace` replaces it
- **AND** no tier is imported, not even partially

#### Scenario: Re-importing the same backup is refused from the ledger

- **WHEN** the same backup is imported a second time
- **THEN** the second run is detected from the ledger and refuses
- **AND** the series are not doubled

### Requirement: `--replace` is one transaction across all three tiers

`local import <dir> --replace` SHALL remove the named cluster's metrics, logs **and** profile blocks
and write the new import as a single unit, so one flag leaves one consistent state per cluster.

The unit SHALL roll back. When it fails at any point, the archive SHALL hold the previous state
exactly as it was before, across all three tiers.

`--replace` names one cluster. It SHALL NOT remove or alter any other cluster's data.

#### Scenario: `--replace` swaps all three tiers

- **GIVEN** the archive holds metrics, logs and profile blocks for cluster `alpha`
- **WHEN** the user runs `local import ./runs --replace`
- **THEN** `alpha`'s metrics, logs and profile blocks are all removed and the new import is written as one unit

#### Scenario: A failed `--replace` leaves the previous state

- **GIVEN** a `--replace` import in progress
- **WHEN** it fails at any point
- **THEN** the archive holds the previous state exactly as before
- **AND** no tier is left half-replaced

#### Scenario: `--replace` does not touch another cluster

- **GIVEN** the archive holds data for clusters `alpha` and `beta`
- **WHEN** the user runs `--replace` against `alpha`
- **THEN** `beta`'s metrics, logs and profiles are unchanged and remain queryable

### Requirement: Import is additive across clusters

Import SHALL add a cluster's data to the aggregate without clearing, truncating or requiring the
absence of another cluster's data. The only removal an import performs is a `--replace` against the
cluster named on the command line.

`vmrestore` writes a full snapshot into an empty directory and cannot merge, so import SHALL restore
into a staging area, merge the series into the live store, record the result, then clear staging.

When an import fails part way, the existing aggregate SHALL be unchanged and `staging/` SHALL be
left in a state a rerun can clear.

#### Scenario: A second cluster is added, not swapped in

- **GIVEN** the aggregate already holds a different cluster's import
- **WHEN** a new cluster's backup is imported
- **THEN** the new import is added
- **AND** both clusters' data remain queryable afterwards

#### Scenario: Two clusters are separable on a dashboard

- **GIVEN** two imports from clusters with different names
- **WHEN** a dashboard is opened against the aggregate
- **THEN** selecting either cluster shows only that cluster's series

#### Scenario: Staging is cleared on success

- **WHEN** an import completes
- **THEN** `staging/` is empty

#### Scenario: A failed import leaves the aggregate unchanged

- **WHEN** an import fails part way
- **THEN** the existing aggregate is unchanged
- **AND** `staging/` is left in a state a rerun can clear

### Requirement: Import never writes into a live data directory

Import SHALL NOT write into a data directory a storage container is currently serving. When the
storage container is running, import SHALL either stop it first or refuse with an instruction naming
what to do.

#### Scenario: A running storage container is handled, not written around

- **GIVEN** the local stack's storage container is running
- **WHEN** the user runs `local import`
- **THEN** the command either stops the container first or refuses with a clear instruction
- **AND** it never writes into the live data directory

### Requirement: The imports ledger

The scaffold SHALL keep an imports ledger under `imports/`, one record per import, naming the
cluster, the snapshot timestamp, the S3 URI and the imported time range. The ledger is what makes a
re-import detectable rather than silently doubled, and it is what `local rm` addresses.

#### Scenario: A completed import is recorded

- **WHEN** an import completes
- **THEN** a record naming the cluster, the timestamp, the S3 URI and the time range exists in `imports/`

### Requirement: `local ls` reports what the archive holds

`local ls <dir>` SHALL list the archive's imports from the ledger, so the user can see which
clusters and snapshots are present and address one with `local rm`. It SHALL run with no cluster
workspace present.

#### Scenario: The archive's imports are listed

- **GIVEN** a scaffold holding two imports
- **WHEN** the user runs `local ls ./runs`
- **THEN** both imports are listed with their cluster, timestamp and time range

### Requirement: `local rm` removes one recorded import

`local rm <dir> <import-id>` SHALL remove the named import's series from the aggregate and remove
its ledger entry. The remaining imports SHALL stay queryable.

Naming an entry that is not in the ledger SHALL fail, listing what is there.

`local rm` is how disk is managed. There SHALL be no time-based retention and no size-based
eviction; the point of the aggregate is that old results survive.

#### Scenario: A named import is removed

- **GIVEN** an aggregate holding more than one import
- **WHEN** the user runs `local rm ./runs <import-id>` naming a ledger entry
- **THEN** that import's series are removed from the aggregate
- **AND** the remaining imports stay queryable
- **AND** the ledger entry is gone

#### Scenario: An unknown import id fails listing what is there

- **WHEN** the user names an import id that is not in the ledger
- **THEN** the command fails listing the entries that are present

### Requirement: `local dashboards` refreshes only the dashboards

`local dashboards <dir>` SHALL rewrite the scaffold's `dashboards/` directory and make the new
versions visible in Grafana without restarting Grafana. It SHALL NOT recreate any storage-tier
container and SHALL NOT lose data.

`--from <dir>` SHALL read dashboards from that directory, so an edit is loaded with no rebuild. With
no `--from` the packaged dashboards are used, and the command SHALL state which copy it used — a
build output is deployable without the user noticing it is stale, and the command must not do that
silently.

Running against a stack that is not running SHALL fail clearly rather than half-updating.

#### Scenario: Dashboards refresh without restarting Grafana

- **GIVEN** a running local stack
- **WHEN** the user runs `local dashboards ./runs`
- **THEN** the scaffold's `dashboards/` directory is rewritten and the new versions are visible in Grafana
- **AND** Grafana is not restarted

#### Scenario: Data and storage containers are untouched

- **WHEN** a dashboards refresh completes
- **THEN** restored metrics, logs and profiles are still queryable
- **AND** no storage-tier container was recreated

#### Scenario: `--from` loads an edit with no rebuild

- **GIVEN** a directory of edited dashboard JSON
- **WHEN** the user runs `local dashboards ./runs --from <dir>`
- **THEN** the dashboards are read from that directory
- **AND** no rebuild is required for them to appear

#### Scenario: The command states which copy it used

- **WHEN** `--from` is omitted
- **THEN** the packaged dashboards are used
- **AND** the command states which copy it deployed

#### Scenario: A stopped stack fails clearly

- **WHEN** the command runs against a stack that is not running
- **THEN** it fails clearly
- **AND** the scaffold's dashboards are not half-updated

### Requirement: Pyroscope profiles are restored locally and served offline

Import SHALL sync Pyroscope profile blocks down from S3 into the scaffold's `data/pyroscope/`
directory, and the local Pyroscope SHALL serve them from a filesystem backend.

Once data is imported, the whole stack SHALL work with no network and no AWS credentials. Pointing
the local Pyroscope at the cluster's S3 bucket is not an acceptable substitute, because it requires
credentials and a network on every start.

#### Scenario: The profiling dashboard works offline

- **GIVEN** profiles have been imported into the scaffold
- **WHEN** the profiling dashboard is opened with the network disconnected and no AWS credentials present
- **THEN** it returns data

### Requirement: Pyroscope's compactor is disabled in the local stack

The local Pyroscope SHALL run with `-compactor.disabled-tenants=anonymous`. Compaction merges blocks
into new ULIDs and deletes the sources, which invalidates the ledger's recorded block identity and
makes per-import profile deletion impossible in principle. No Pyroscope version offers
delete-by-selector, so block-directory removal is the only deletion route that exists, and it
depends on block identity staying stable.

The two consequences SHALL be documented: the store-gateway falls back to direct bucket scans, so
queries keep working while syncs get slower; and the querier's `GetProfileStats` has no such
fallback, so the UI's data-availability and default-time-range hint sees nothing in storage. Flame
graphs render normally when a time range is set explicitly.

#### Scenario: Block identity is stable across restarts

- **GIVEN** profile blocks imported into the local Pyroscope
- **WHEN** the stack is restarted and left running
- **THEN** the block ULIDs recorded in the ledger still identify the same blocks on disk
- **AND** no block was merged, rewritten or deleted

#### Scenario: The UI hint gap is documented, not silently lived with

- **WHEN** a user opens the local Pyroscope UI with no explicit time range
- **THEN** the documentation states that the data-availability hint sees nothing because the compactor is disabled
- **AND** it states that setting an explicit time range renders flame graphs normally

### Requirement: Import stamps the cluster label as a fill

Import SHALL stamp the derived cluster value onto records that carry no `cluster` label, so that
data from collector pipelines which do not emit the label is still filterable by cluster in the
aggregate. This covers the `metrics/spanmetrics`, `metrics/servicegraph` and `logs/local` gaps with
no collector change.

The stamp SHALL be a **fill**, never an overwrite. On a snapshot carrying a mix of labelled and
unlabelled series, only the unlabelled ones are stamped and the labelled ones keep their original
value.

The cluster value SHALL be resolved by reading the `cluster` label from the backup's own contents
over an explicit all-time range, cross-checked against the S3 key, and from an explicit flag when
neither is available. Resolution from the key SHALL anchor on the literal tier directory name and
never on position: the segment immediately before `victoriametrics` or `victorialogs`, or the first
key segment with `pyroscope.` stripped.

#### Scenario: Unlabelled records are stamped

- **GIVEN** a backup whose records lack a `cluster` label
- **WHEN** it is imported
- **THEN** the import stamps the resolved cluster value onto those records
- **AND** a dashboard can filter that data by cluster

#### Scenario: A mixed snapshot fills without overwriting

- **GIVEN** a snapshot carrying a mix of labelled and unlabelled series
- **WHEN** it is imported
- **THEN** only the unlabelled series are stamped
- **AND** the labelled series keep their original `cluster` value

#### Scenario: The key is read by tier name, not by position

- **GIVEN** an S3 key whose last segment is the snapshot timestamp
- **WHEN** the cluster value is resolved from the key
- **THEN** it is taken from the segment immediately before `victoriametrics` or `victorialogs`, or from the first key segment with `pyroscope.` stripped
- **AND** it is never taken from the last segment by position

### Requirement: Import refuses when the cluster identity cannot be trusted

Import SHALL refuse rather than guess, and each refusal SHALL name what it needs or what it found. A
wrong stamp does not fail loudly — the label write succeeds — so a guess would silently relabel
correctly-labelled data.

The four cases are:

1. The backup was taken with `--destination` to an arbitrary S3 location, so no cluster identity is
   present in the key and none is available from an explicit flag.
2. The records already carry a `cluster` label that disagrees with the value derived from the S3
   prefix. The refusal names both values.
3. A single backup carries more than one `cluster` label value. The refusal lists them, rather than
   merging them under one name.
4. The cluster value cannot be resolved from the backup's contents, the key, or a flag.

#### Scenario: No cluster identity in the key is refused

- **GIVEN** a backup taken with `--destination` to an arbitrary S3 location, with no cluster identity in the key
- **WHEN** it is imported with no explicit cluster flag
- **THEN** the import refuses and names what it needs
- **AND** it does not stamp a guessed value

#### Scenario: Label and key disagreement is refused

- **GIVEN** a backup whose records carry a `cluster` label that disagrees with the value derived from its S3 prefix
- **WHEN** it is imported
- **THEN** the import refuses, naming both values

#### Scenario: More than one label value in one backup is refused

- **GIVEN** a single backup found to carry more than one `cluster` label value
- **WHEN** it is imported
- **THEN** the import refuses and lists the values it found
- **AND** it does not merge them under one name

### Requirement: Every local store carries unbounded retention

Every store the scaffold generates SHALL be configured with `-retentionPeriod=100y`, and so SHALL
every transient container the tool starts to read a restored snapshot. VictoriaLogs SHALL additionally carry
`-futureRetention=100y`. The unit SHALL always be written, because a bare number means *months* in
both products.

VictoriaMetrics enforces retention as a sliding window against the current clock rather than against
ingest time, so a restored backup older than the window is silently dropped; VictoriaLogs
additionally rejects out-of-retention records at ingest. A staging store left on the default is the
first thing to truncate, before anything reads it.

Import SHALL check the retention of each store it writes to. When a store's retention is not the
unbounded value, import SHALL refuse, naming the container and both values.

#### Scenario: Generated stores carry the unbounded value

- **WHEN** the scaffold is generated
- **THEN** every store's retention is `100y`
- **AND** VictoriaLogs additionally carries `-futureRetention=100y`

#### Scenario: A transient container carries it too

- **WHEN** a transient container is started to read a restored snapshot
- **THEN** it carries the unbounded retention value as well

#### Scenario: A bounded store is refused

- **WHEN** import runs against a store whose retention is not the unbounded value
- **THEN** it refuses, naming the container and both values

#### Scenario: The unit is always written

- **WHEN** any retention value the tool generates is inspected
- **THEN** it carries an explicit unit
- **AND** no bare number is used, because a bare number means months

### Requirement: The local workflow is documented end to end

The user documentation SHALL carry the local workflow, so that a reader goes from "no cluster, a
backup in S3" to "a dashboard open in local Grafana showing that backup's data" without reading
source.

It SHALL demonstrate the accumulating behaviour rather than assert it, by showing two runs imported
into one directory and a dashboard selecting between them.

It SHALL document the consequences of disabling Pyroscope's compactor: the store-gateway's fallback
to direct bucket scans, and that `GetProfileStats` has no fallback, so the UI's data-availability
hint sees nothing while flame graphs render normally with an explicit time range.

#### Scenario: A reader gets from S3 to a dashboard

- **GIVEN** a user with no cluster running and a backup in S3
- **WHEN** they follow the documentation page end to end
- **THEN** they reach a dashboard open in local Grafana showing that backup's data
- **AND** they do not have to read source to get there

#### Scenario: Accumulation is shown, not asserted

- **WHEN** the documentation describes accumulating results
- **THEN** it shows two runs imported into one directory
- **AND** it shows a dashboard selecting between them

### Requirement: Nothing evicts from the local aggregate

The local aggregate SHALL grow only. No time-based retention, no size ceiling, and no eviction
policy of any kind SHALL apply to it, on any store, at any time.

The only two removal paths that SHALL exist are an explicit `local rm` naming an import and an
explicit `local import --replace` naming a cluster. Both are user-initiated and both name what they
remove.

#### Scenario: Age and size remove nothing

- **GIVEN** a local aggregate that has grown large and holds old imports
- **WHEN** the stack runs over a long period
- **THEN** nothing is evicted on age
- **AND** nothing is evicted on size

#### Scenario: Only two removal paths exist

- **WHEN** the ways data leaves the aggregate are enumerated
- **THEN** they are exactly an explicit `local rm` naming an import and an explicit `local import --replace` naming a cluster
