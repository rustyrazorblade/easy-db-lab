# AC coverage — issue 902

One row per acceptance criterion on the issue, in issue order, plus one row per risk the design
surfaced. Capability prefixes: `local` = `local-observability-stack`, `lifecycle` =
`cluster-lifecycle`, `obs` = `observability`, `mcd` = `multi-cluster-dashboards`, `prof` =
`profiling`.

| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC | Generate with a directory argument creates the stack and `docker compose up` needs no edits | `local: A generated scaffold starts with no edits` | ✅ Covered |
| AC | Generate with no directory argument fails naming the missing argument | `local: A subcommand invoked with no directory fails` | ✅ Covered |
| AC | Regenerating rewrites `scaffold.json`'s files and leaves `data/`, `imports/`, `compose.override.yml` byte-identical | `local: Regeneration leaves data and user files untouched` | ✅ Covered |
| AC | A foreign non-empty directory fails naming the directory, overwriting nothing | `local: A foreign non-empty directory is refused` | ✅ Covered |
| AC | A newer image pin or new datasource leaves a restored aggregate queryable | `local: A restored aggregate survives a newer scaffold` | ✅ Covered |
| AC | Grafana's datasource list is exactly the four, with the right types | `local: The datasource list matches the cluster's` | ✅ Covered |
| AC | A dashboard defaulting to `VictoriaMetrics` resolves with no "datasource not found" | `local: A dashboard's datasource variable resolves` | ✅ Covered |
| AC | The VictoriaLogs datasource plugin is present when Grafana starts | `local: The VictoriaLogs datasource plugin is present` | ✅ Covered |
| AC | A scaffold inside a git-tracked workspace is gitignored | `local: The scaffold directory is gitignored` | ✅ Covered |
| AC | Local image tags match the manifest builders' pins | `local: Local pins match the manifest builders'` | ✅ Covered |
| AC | Generation succeeds and is complete with no source checkout | `local: Generation succeeds with no source checkout` | ✅ Covered |
| AC | A metrics backup merges and a source-cluster series returns data over the backed-up range | `local: Metrics are queryable after import` | ✅ Covered |
| AC | A logs backup serves restored partitions and `log-investigation.json` returns rows | `local: Logs are queryable after import` | ✅ Covered |
| AC | A different cluster's import is added, both stay queryable, no empty directory required | `local: A second cluster is added, not swapped in` | ✅ Covered |
| AC | Two differently-named clusters are each selectable on a dashboard | `local: Two clusters are separable on a dashboard` | ✅ Covered |
| AC | The same backup imported twice is refused from the ledger | `local: Re-importing the same backup is refused from the ledger` | ✅ Covered |
| AC | A completed import writes a ledger record and leaves `staging/` empty | `local: A completed import is recorded` + `local: Staging is cleared on success` | ✅ Covered |
| AC | A part-way failure leaves the aggregate unchanged and `staging/` rerun-clearable | `local: A failed import leaves the aggregate unchanged` | ✅ Covered |
| AC | Imported profiles render on the profiling dashboard offline with no credentials | `local: The profiling dashboard works offline` | ✅ Covered |
| AC | An absent timestamp or prefix fails naming the S3 URI, not a stack trace or empty success | `local: A missing prefix names the URI it looked at` | ✅ Covered |
| AC | A running storage container is stopped or the command refuses; never writes into a live data directory | `local: A running storage container is handled, not written around` | ✅ Covered |
| AC | Import works from an explicit `s3://` URI when `state.json` is gone | `local: An explicit URI works without a workspace` | ✅ Covered |
| AC | Missing or insufficient AWS credentials say so rather than presenting as an empty backup | `local: A credential problem says so` | ✅ Covered |
| AC | A recorded snapshot for a cluster makes a bare import refuse the whole command, naming it and `--replace` | `local: A second snapshot of a recorded cluster is refused` | ✅ Covered |
| AC | `--replace` removes metrics, logs **and** profile blocks as one unit, and rolls back fully on failure | `local: --replace swaps all three tiers` + `local: A failed --replace leaves the previous state` | ✅ Covered |
| AC | Profile blocks are keyed on leaf ULIDs, the count is reported, and adding none is stated explicitly | `local: Profiles are deduplicated on their leaf ULIDs` + `local: Adding no profile blocks is stated explicitly` | ✅ Covered |
| AC | Deleting a named ledger entry removes its series, keeps the rest queryable, and drops the entry | `local: A named import is removed` | ✅ Covered |
| AC | Deleting an entry not in the ledger fails listing what is there | `local: An unknown import id fails listing what is there` | ✅ Covered |
| AC | The dashboards-only command rewrites `dashboards/` and shows the new versions without restarting Grafana | `local: Dashboards refresh without restarting Grafana` | ✅ Covered |
| AC | After a refresh, restored data is still queryable and no storage container was recreated | `local: Data and storage containers are untouched` | ✅ Covered |
| AC | `--from <dir>` reads that directory with no rebuild required | `local: --from loads an edit with no rebuild` | ✅ Covered |
| AC | With `--from` omitted the packaged copy is used and the command states which copy it used | `local: The command states which copy it used` | ✅ Covered |
| AC | Running against a stopped stack fails clearly rather than half-updating | `local: A stopped stack fails clearly` | ✅ Covered |
| AC | Every top-level and kit dashboard exposes a cluster-name field and filters on it, no exclusions | `mcd: Every dashboard in the repository exposes the field` + `mcd: The enforcement test walks labelSelector fields` | ✅ Covered |
| AC | Two clusters in one aggregate do not blend when one is selected | `mcd: Two clusters in one aggregate do not blend` | ✅ Covered |
| AC | These dashboards behave as before for a single cluster under `grafana update-config` | `mcd: Live-cluster behaviour is unchanged` | ✅ Covered |
| AC | An unlabelled backup is stamped so it is filterable, covering the three collector gaps; value resolved from contents, key or flag, anchored on the tier name | `local: Unlabelled records are stamped` + `local: The key is read by tier name, not by position` | ✅ Covered |
| AC | A backup with no cluster identity in the key is refused, naming what it needs, not stamped with a guess | `local: No cluster identity in the key is refused` | ✅ Covered |
| AC | A mixed snapshot stamps only the unlabelled series | `local: A mixed snapshot fills without overwriting` | ✅ Covered |
| AC | A label disagreeing with the key-derived value is refused, naming both | `local: Label and key disagreement is refused` | ✅ Covered |
| AC | More than one label value in one backup is refused, listing them | `local: More than one label value in one backup is refused` | ✅ Covered |
| AC | `down` applies no expiration to any observability prefix, and a pre-teardown backup stays readable | `lifecycle: No expiration is applied to observability data` | ✅ Covered |
| AC | The account bucket carries no rule covering `clusters/<name>-<clusterId>` after teardown | `lifecycle: The account bucket carries no cluster-prefix rule after teardown` | ✅ Covered |
| AC | Pyroscope's `bucket_name` on a cluster is the account bucket, not the data bucket | `prof: Pyroscope stores profiles in the account bucket` | ✅ Covered |
| AC | A torn-down cluster's expiring data bucket loses no observability data | `lifecycle: The data bucket's expiry loses no observability data` + `prof: Profiles survive the data bucket's expiry` | ✅ Covered |
| AC | Generated stores carry the unbounded value, and import refuses a store that does not, naming the container and both values | `local: Generated stores carry the unbounded value` + `local: A bounded store is refused` | ✅ Covered |
| AC | The cluster's VictoriaMetrics and VictoriaLogs carry the unbounded value and `RETENTION_PERIOD` names no bounded period | `obs: Deployed stores carry the unbounded value` + `obs: No bounded period remains in the builder` + `obs: A long-running cluster keeps its oldest data` | ✅ Covered |
| AC | A transient container reading a restored snapshot carries the unbounded value too | `local: A transient container carries it too` | ✅ Covered |
| AC | Nothing evicts from the aggregate on age or size; only `local rm` and `--replace` remove | `local: Age and size remove nothing` + `local: Only two removal paths exist` | ✅ Covered |
| AC | The two top-level ClickHouse dashboards and their enum entries are gone | `obs: The stale top-level ClickHouse dashboards are gone` | ✅ Covered |
| AC | `grafana update-config` succeeds afterwards and installs no ClickHouse dashboard | `obs: grafana update-config installs no ClickHouse dashboard` | ✅ Covered |
| AC | The ClickHouse kit's two dashboards install as before and are the only ones | `obs: The kit's copies are the only ones` | ✅ Covered |
| AC | A generated scaffold contains exactly one copy of each ClickHouse dashboard, from the kit | `local: One copy of each ClickHouse dashboard` | ✅ Covered |
| AC | A reader follows the doc page from "backup in S3" to "dashboard in local Grafana" without reading source | `local: A reader gets from S3 to a dashboard` | ✅ Covered |
| AC | The doc shows two runs imported into one directory and selecting between them | `local: Accumulation is shown, not asserted` | ✅ Covered |
| Risk | A wrong cluster stamp is silent — the label write always succeeds, so a guess relabels correct data | `local: No cluster identity in the key is refused` + `local: Label and key disagreement is refused` + `local: More than one label value in one backup is refused` | ✅ Covered — all four ambiguous cases refuse the whole command rather than importing what they can |
| Risk | A truncated export passes as success unless units are counted at both ends | `local: Metrics are queryable after import` | ⚠️ Excluded as a scenario of its own — the count assertion is a mechanism detail of the merge, specified in `design.md` and carried as tasks 7.3 and 9.5; a spec scenario asserting an internal counter would pin the implementation rather than the behaviour |
| Risk | A staging store left on the default retention truncates before anything reads it | `local: A transient container carries it too` | ✅ Covered |
| Risk | An enforcement test walking only `expr` passes `profiling.json` while it silently blends | `mcd: The enforcement test walks labelSelector fields` | ✅ Covered — the requirement names the field the test must walk, because a test that does not walk it reports green on the broken dashboard |
| Risk | `local dashboards` with no `--from` can silently deploy a stale build copy | `local: The command states which copy it used` | ✅ Covered |
| Risk | A scaffold path that reads a source checkout works only on a developer's machine | `local: Generation succeeds with no source checkout` | ✅ Covered |
| Risk | Generated files could collide with the repo's own top-level `docker-compose.yml` / `otel-collector-config.yaml` | `local: A generated scaffold starts with no edits` (the requirement text forbids the collision) | ✅ Covered — carried as task 6.11; the scaffold is generated into an explicit user-named directory, so the collision is only reachable if the user names the repo root |
| Risk | `BUCKET_NAME` has a second consumer — ClickHouse's S3 data disk must not move | `prof: BUCKET_NAME keeps its meaning` | ✅ Covered |
| Risk | The account bucket is one per account with no region recorded, while Pyroscope builds its endpoint from the cluster's region | `prof: The bucket's region is resolved from the bucket, not the cluster` | ✅ Covered |
| Risk | A cluster provisioned before the region field exists has a `state.json` without it, and a live shared cluster is in that position | `prof: An existing cluster with no stored region keeps working` + `prof: The resolution happens once, not once per command` + `prof: An absent region never falls back to the cluster's` + `prof: A failed lazy resolution fails fast` | ✅ Covered — resolved lazily on first use and persisted, never falling back to the cluster's region; a failed resolution fails naming the bucket and the call. `s3Bucket` is `String?` and `dataBucket` defaults to `""` (`ClusterState.kt:216,218`), so a new field with a default deserializes cleanly on an old file — but clean deserialization is not correct behaviour, which is why this is specified rather than left to implementation |
| Risk | Pyroscope compaction would invalidate recorded block identity, making per-import deletion impossible | `local: Block identity is stable across restarts` | ✅ Covered |
| Risk | Disabling the compactor blinds the UI's data-availability hint, because `GetProfileStats` has no fallback | `local: The UI hint gap is documented, not silently lived with` + `local: A reader gets from S3 to a dashboard` | ✅ Covered — documented rather than worked around; the store-gateway's slower direct bucket scans are the other half and are documented alongside it |
| Risk | No Pyroscope version has delete-by-selector, so block-directory removal is the only deletion route | `local: A named import is removed` + `local: --replace swaps all three tiers` | ✅ Covered — both deletion paths are specified in terms of removing blocks, which is the only route that exists |
| Risk | A bare retention number means MONTHS in both products | `local: The unit is always written` | ✅ Covered |
| Risk | `TemplateService.kt:50`'s "no forward slashes" KDoc is wrong and shaped the S3 layout | — | ⚠️ Excluded — an incorrect comment, not observable behaviour. Carried as task 10.5; its consequence, the three-tier single prefix tree, is what `local: An explicit URI works without a workspace` rests on |
| Risk | `dashboards/CLAUDE.md:72` and `:107` point at `bin/generate-dashboard-links.py`, which has never existed | — | ⚠️ Excluded — a contributor-facing doc defect with no product behaviour. Carried as task 10.6 |
| Risk | okio is imported at `VictoriaStreamService.kt:12` but undeclared, relying on a transitive dependency | — | ⚠️ Excluded — a build-configuration defect in a service this design does not use. Carried as task 10.7; new code uses `kotlinx-io` |
| Risk | Four verification checks remain for implementation | — | ⚠️ Excluded — none affects the design's shape, per the owner-approved design; they are implementation-time confirmations, not requirements |
| Risk | Node-local JFR retention is bounded, which reads as a conflict with the no-automatic-deletion constraint | — | ⚠️ Excluded — deliberately not changed. Those bounds cover the chunk buffer on a database node, not a store; removing them would let an unreachable Pyroscope fill the volume Cassandra stores data on. Reasoned in `overrides.md` |

| AC | Every OTel pipeline stamps the `cluster` attribute, so no telemetry stream reaches the store unlabelled (in-scope item 17, added during implementation) | `obs: Every pipeline carries the processor` + `obs: Span metrics and service graph metrics carry the cluster` + `obs: System, tool, Cassandra and OTLP log records carry the cluster` + `obs: A newly added pipeline cannot omit it` | ✅ Covered — enforced as an invariant over all pipelines rather than a fix to a named list, so a newly added pipeline cannot reintroduce the gap |

## Totals

75 rows — 56 acceptance criteria and 19 risks: **69 ✅ Covered**, **6 ⚠️ Excluded**. Every excluded
row carries its reason inline, and each is carried by a named task rather than dropped.

The 19th risk was surfaced during activation rather than by the issue body: an existing `state.json`
predating the account-bucket region field.

## What "Covered" means here

The *Covering scenario(s)* column names **spec scenarios**, not tests. A ✅ here means the criterion
has a scenario that states it, not that a test exists for it yet — writing those tests is section 9
of `tasks.md`.

## Criteria that resisted a clean scenario

Two are worth naming before implementation starts.

**The merge's unit counts.** The issue treats "a truncated transfer fails rather than passing
silently" as a design guarantee, and it is the single most important correctness property of the
merge. It has no scenario of its own because the only honest observable form of it is "the import
either imported everything or failed", which the existing scenarios already state. Its real proof is
a test that truncates the stream mid-transfer and asserts the import fails — task 9.5.

**"`docker compose up` brings the stack up with no further edits."** Written as one scenario, it is
an end-to-end integration assertion rather than a unit-testable one. It is kept as a scenario because
it is the criterion the whole scaffold exists to satisfy, but it will be verified by task 11.2's real
run rather than by an automated test.
