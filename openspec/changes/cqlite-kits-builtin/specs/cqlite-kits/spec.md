## ADDED Requirements

### Requirement: Built-in kit discovery without external sources

The three cqlite kits SHALL be discoverable as built-in kits from the classpath resource root, requiring no `kit source add` registration.

#### Scenario: All three kits discoverable with no external source
- **WHEN** the project is built and kit discovery runs on a checkout with no `kit source add` registered
- **THEN** `cqlite-flight`, `cqlite-trino`, and `trino-loadtest` all appear as available built-in kits with `kit install` subcommands

#### Scenario: Each kit descriptor parses at build time
- **WHEN** the resolver loads each of the three new built-in kit sources
- **THEN** `loadInstallConfig` parses each `kit.yaml` without error

#### Scenario: Nested gradle-assemble resources are listed
- **WHEN** the resolver lists template files for the `cqlite-trino` built-in source
- **THEN** the listing includes `gradle-assemble-plugin/build.gradle.kts.template` and `gradle-assemble-plugin/settings.gradle.kts`

### Requirement: cqlite-flight runs on every db node

The `cqlite-flight` kit (`type: db`) SHALL run an Arrow Flight server on every db node with the Cassandra data directory mounted into the container, ported verbatim from its external form.

#### Scenario: Install on a cluster with db nodes
- **WHEN** `kit install cqlite-flight` runs on a cluster with Cassandra db nodes and no external kit source
- **THEN** it installs without error and `kit info` lists it as installed

#### Scenario: One Flight pod per db node with data dir mounted
- **WHEN** `cqlite-flight` starts
- **THEN** an Arrow Flight server pod is running on every db node (DaemonSet, `nodeSelector: type=db`) with the Cassandra data directory mounted into the container

### Requirement: cqlite catalog registration and dependency

The `cqlite-trino` kit SHALL register a Trino catalog named `cqlite` additively alongside the existing `cassandra` catalog, and SHALL fail fast when its Trino dependency is absent. The kit resource directory is named `cqlite-trino` while its `kit.yaml` name (and therefore the installed catalog) is `cqlite`.

#### Scenario: Install subcommand vs catalog name
- **WHEN** the kit is installed
- **THEN** the install subcommand is `kit install cqlite-trino` (from the resource directory name) and the installed kit and Trino catalog are named `cqlite` (from `kit.yaml` `name: cqlite`), giving clean `SELECT ... FROM cqlite.<ks>.<tbl>` addressing

#### Scenario: Fail fast when Trino is not running
- **WHEN** the `cqlite-trino` kit is installed/started and the Trino kit is not running
- **THEN** it fails fast with an error naming the missing Trino dependency and does not register a broken catalog

#### Scenario: Catalog registered additively
- **WHEN** the `cqlite-trino` kit starts against a running Trino kit
- **THEN** `SHOW CATALOGS` lists `cqlite` in addition to the existing `cassandra` catalog, which remains present and unchanged

#### Scenario: Gradle-assemble plugin resolves from the classpath
- **WHEN** the `cqlite-trino` kit start triggers the `gradle-assemble-plugin` assembly path from a built-in (classpath) install
- **THEN** the connector plugin directory is assembled from the published `in.mcfad:cqlite-trino` artifact and loaded into Trino with no "file not found" / missing-source error

### Requirement: Read-only offline query surface

A query against the `cqlite` catalog SHALL return rows read from flushed SSTables via Arrow Flight, aggregated across db nodes.

#### Scenario: SELECT returns rows from SSTables
- **WHEN** a `SELECT` runs against a `cqlite` catalog table that has flushed SSTables
- **THEN** rows are returned, read from SSTables via Arrow Flight and aggregated across db nodes

### Requirement: trino-loadtest generic read-load driver

The `trino-loadtest` kit SHALL drive concurrent read load against a running Trino kit's cqlite catalog and report throughput/latency, shipping `driver.py` only (not `test_driver.py`).

#### Scenario: Load driver runs against a Trino target
- **WHEN** `trino-loadtest` is installed with `--target <running-trino-kit>` and started, with no external kit source registered
- **THEN** it drives concurrent read load against the cqlite catalog and reports throughput/latency statistics

#### Scenario: Test file not shipped
- **WHEN** the `trino-loadtest` built-in kit resources are listed
- **THEN** `driver.py` is present and `test_driver.py` is absent

### Requirement: Lifecycle teardown restores prior state

Each kit's stop/uninstall SHALL remove its resources and return the catalog list and cluster state to their prior condition.

#### Scenario: Stop and uninstall clean up
- **WHEN** `kit stop` / `kit uninstall` runs for each kit
- **THEN** its resources are removed, the catalog list / cluster state returns to its prior condition, and any `easydblab-metrics-<kit>` ConfigMap is deleted

### Requirement: Documented offline semantics

The kit docs SHALL state the offline, read-only, flushed-SSTables-only, and eventually-stale semantics so results are not mistaken for a live consistent view.

#### Scenario: Docs state the semantics
- **WHEN** the kit docs are read
- **THEN** they state the offline / read-only / flushed-only / eventually-stale semantics

#### Scenario: Each kit has a user-guide page registered in nav
- **WHEN** the user documentation is browsed
- **THEN** each of the three kits has a `docs/user-guide/<kit>.md` page registered in `docs/SUMMARY.md`, following the existing per-kit page convention

### Requirement: Test plans depend only on in-tree kits

The in-tree cqlite test plans SHALL NOT depend on external kit sources.

#### Scenario: Production-readiness plan runs without external kits
- **WHEN** `test-plans/cqlite-flight-production-readiness.md` is run end-to-end
- **THEN** it completes without any `kit source add` step and without referencing the external `pmcfadin/cqlite` tree

#### Scenario: Version-pinned checkpoint plans retired
- **WHEN** the `test-plans/` directory is listed
- **THEN** `cqlite-flight-milestone-snapshot-0.15.md` and `cqlite-flight-0.16.0-rc1-fixvalidation.md` are absent, and the general production-readiness plan is the sole committed cqlite plan

#### Scenario: No external-kit references remain in any cqlite plan
- **WHEN** any committed `test-plans/*cqlite*.md` is grepped for `kit source add` or `pmcfadin/cqlite/easy-db-lab-kits`
- **THEN** there are zero matches

#### Scenario: General plan stands alone with no out-of-tree runbook dependency
- **WHEN** the general production-readiness plan's prelude is read after the checkpoint plans are retired
- **THEN** its durable setup guidance (tunnels/keepalive, Cassandra step ordering, digest discipline) is present in-tree and it does not depend on the untracked `clusters/HANDOFF-cqlite-lab-testing.md` runbook
