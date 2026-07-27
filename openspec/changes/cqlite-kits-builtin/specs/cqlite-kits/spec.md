## ADDED Requirements

### Requirement: Built-in kit discovery without external sources

The `cqlite-flight` and `trino-loadtest` kits SHALL be discoverable as built-in kits from the classpath resource root, requiring no `kit source add` registration. cqlite Trino integration SHALL be delivered as a catalog property file of the trino kit, not as a standalone kit.

#### Scenario: Both kits discoverable with no external source
- **WHEN** the project is built and kit discovery runs on a checkout with no `kit source add` registered
- **THEN** `cqlite-flight` and `trino-loadtest` appear as available built-in kits with `kit install` subcommands

#### Scenario: No standalone cqlite-trino kit
- **WHEN** kit discovery runs
- **THEN** there is no `cqlite-trino` built-in kit and no `kit install cqlite-trino` subcommand; resolving `cqlite-trino` fails as an unknown template

#### Scenario: Each kit descriptor parses at build time
- **WHEN** the resolver loads the `cqlite-flight` and `trino-loadtest` built-in kit sources
- **THEN** `loadInstallConfig` parses each `kit.yaml` without error

#### Scenario: cqlite catalog file is listed for the trino kit
- **WHEN** the resolver lists template files for the `trino` built-in source
- **THEN** the listing includes `catalogs/cqlite.properties.template` alongside `catalogs/cassandra.properties.template`

### Requirement: cqlite-flight runs on every db node

The `cqlite-flight` kit (`type: db`) SHALL run an Arrow Flight server on every db node with the Cassandra data directory mounted into the container, ported verbatim from its external form.

#### Scenario: Install on a cluster with db nodes
- **WHEN** `kit install cqlite-flight` runs on a cluster with Cassandra db nodes and no external kit source
- **THEN** it installs without error and `kit info` lists it as installed

#### Scenario: One Flight pod per db node with data dir mounted
- **WHEN** `cqlite-flight` starts
- **THEN** an Arrow Flight server pod is running on every db node (DaemonSet, `nodeSelector: type=db`) with the Cassandra data directory mounted into the container

### Requirement: cqlite is a trino catalog, with deferred plugin delivery

cqlite Trino integration SHALL be delivered as a catalog property file of the trino kit (`kits/trino/catalogs/cqlite.properties.template`), auto-discovered by the trino kit's own `update-catalogs.sh` and applied via its single `helm upgrade` — exactly like the cassandra/clickhouse/opensearch/tidb catalogs. The catalog SHALL specify `connector.name=cqlite_flight`. The actual `cqlite_flight` connector plugin delivery is DEFERRED and blocked on pmcfadin/cqlite#2869.

#### Scenario: cqlite is a catalog, not a kit
- **WHEN** the cqlite Trino integration is inspected
- **THEN** it is the trino kit's `catalogs/cqlite.properties` file (naming the `cqlite` catalog and `connector.name=cqlite_flight`), not a standalone kit, and there is no `kit install cqlite-trino` command

#### Scenario: Plugin delivery deferred to the fat jar
- **WHEN** the `cqlite` catalog file is examined before pmcfadin/cqlite#2869 is delivered
- **THEN** it ships as a staged template with a documented TODO for the fat-jar hostPath mount, and the trino kit contains no pod-start Gradle resolve (`gradle-assemble-plugin`), no `trino-values.yaml` initContainer fragment, and no fabricated jar URL

#### Scenario: Intended additive registration once wired
- **WHEN** pmcfadin/cqlite#2869 delivers the connector fat jar and the `cqlite` catalog is enabled against a running Trino kit
- **THEN** `SHOW CATALOGS` SHALL list `cqlite` in addition to the existing `cassandra` catalog, which remains present and unchanged, giving `SELECT ... FROM cqlite.<ks>.<tbl>` addressing

### Requirement: Read-only offline query surface

Once the connector plugin is delivered (pmcfadin/cqlite#2869), a query against the `cqlite` catalog SHALL return rows read from flushed SSTables via Arrow Flight, aggregated across db nodes.

#### Scenario: SELECT returns rows from SSTables (blocked on #2869)
- **WHEN** the `cqlite_flight` plugin is present and a `SELECT` runs against a `cqlite` catalog table that has flushed SSTables
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
- **THEN** `cqlite-flight` and `trino-loadtest` each have a `docs/user-guide/<kit>.md` page registered in `docs/SUMMARY.md`, and the `cqlite` catalog is documented in the trino kit's `docs/user-guide/install-trino.md` page (there is no standalone `cqlite-trino` page)

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
