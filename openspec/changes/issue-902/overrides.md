# Overrides and conflicts — issue 902

## Overrides existing behavior

### cluster-lifecycle: Cluster Teardown

**Currently** (`openspec/specs/cluster-lifecycle/spec.md:52-79`):

> The system MUST clean up all AWS resources on cluster teardown. Data bucket cleanup MUST use
> lifecycle expiration rather than individual object deletion.

Four scenarios: AWS resources terminated; the data bucket expires via a lifecycle rule; teardown of
all clusters; teardown requires confirmation. The requirement says nothing about *which* prefixes may
carry an expiration, and the implementation currently applies one to the account bucket's
`clusters/<name>-<clusterId>` prefix — which holds every metrics and logs backup — with
`--retention-days` defaulting to `1`.

**This change:** the same guarantee, plus an explicit prohibition:

> Teardown MUST NOT apply an S3 lifecycle expiration to any prefix that holds metrics, logs, traces
> or profile data. In particular, no lifecycle rule is applied to the account bucket's
> `clusters/<name>-<clusterId>` prefix … nor to any sibling prefix holding profile data. S3 measures
> `Expiration.Days` from object *creation*, so applying such a rule at teardown expires anything
> already older than the window at the next evaluation.
>
> The per-cluster **data** bucket keeps its existing whole-bucket expiry — it is ephemeral by design
> — but no observability data may live there.

All four existing scenarios are preserved verbatim, including "Data bucket expires via lifecycle
rule": the data bucket's whole-bucket expiry is unchanged, and item 15 is what makes keeping it safe.
Three scenarios are added — no expiration over observability prefixes, no cluster-prefix rule on the
account bucket after teardown, and no observability data lost when the data bucket expires.

**User-visible change:** `down --retention-days` no longer applies to the account bucket's cluster
prefix. It keeps its meaning for the data bucket.

### observability: Grafana Dashboards

**Currently** (`openspec/specs/observability/spec.md:19-69`):

> The system MUST provide pre-configured Grafana dashboards for all supported databases and
> infrastructure. Dashboard titles MUST use simple descriptive names without cluster name prefixes.
> The Grafana pod SHALL include an image renderer sidecar for server-side panel rendering. Dashboard
> JSON SHALL be loaded directly from classpath resources without template substitution, preserving
> Grafana built-in variables like `$__rate_interval`.
>
> All dashboards SHALL include a `cluster` multi-select variable and an ad hoc filters variable. All
> VictoriaMetrics-backed panel queries SHALL be scoped by `{cluster=~"$cluster"}`. No native
> ClickHouse datasource SHALL be provisioned.

Six scenarios, none of which names any individual dashboard. "All supported databases" is what today
installs the two top-level ClickHouse dashboards through the `GrafanaDashboard` enum, in addition to
the ClickHouse kit's own two copies of the same dashboards.

**This change:** the requirement text is unchanged and a paragraph is appended:

> The two stale top-level ClickHouse dashboards SHALL NOT be installed. `dashboards/clickhouse.json`
> and `dashboards/clickhouse-logs.json` are removed along with the `CLICKHOUSE` and
> `CLICKHOUSE_LOGS` entries in the `GrafanaDashboard` enum, so `grafana update-config` installs no
> ClickHouse dashboard. The ClickHouse kit's own two dashboards, installed by the kit runner, are the
> only ClickHouse dashboards present.

All six existing scenarios are preserved verbatim. Three are added: the files and enum entries are
gone; `grafana update-config` still succeeds and installs no ClickHouse dashboard; the kit's copies
are the only ones.

**This does not weaken "dashboards for all supported databases".** ClickHouse still has both
dashboards; they arrive through the kit, which is the authoritative copy. `clickhouse.json` was
byte-identical to the kit copy, and `clickhouse-logs.json` had already diverged in the kit's favour
— three panel links and one differing `expr` the top-level copy lacks.

**This is an owner-approved exception** to the change's otherwise-local scope, taken because leaving
the files would mean fixing the same two dashboards twice under the cluster-name work, and shipping a
known-stale copy into the local scaffold.

### observability: The cluster's storage tier retains observability data indefinitely — ADDED, not MODIFIED

Item 16 raises `VictoriaManifestBuilder.RETENTION_PERIOD` from `"7d"` to the unbounded value. **The
`observability` baseline has no requirement about store retention to modify.** Its requirements are
OTel scrape configuration, Grafana dashboards, Cilium as the CNI, hostPort exposure, the OTel
ClusterIP Service, and log collection; none names VictoriaMetrics' or VictoriaLogs' deployment
arguments, and no other capability spec does either.

The retention rule is therefore filed as an `## ADDED Requirements` section in `observability` rather
than as a MODIFIED one. It overrides implementation behaviour, not a committed requirement: nothing
in any spec promised seven days, so nothing is being contradicted. Recorded here because it is an
override of shipped behaviour even though it is not an override of spec text.

### multi-cluster-dashboards: All dashboards have a cluster multi-select variable

**Currently** (`openspec/specs/multi-cluster-dashboards/spec.md:9-35`):

> Every Grafana dashboard SHALL include a `cluster` template variable (lowercase) that queries all
> available cluster values from VictoriaMetrics. The variable SHALL support multi-select and SHALL
> include an "All" option that defaults to all clusters.

Four scenarios: the variable is present on all dashboards; a single-cluster deployment shows one
option; a multi-cluster deployment shows all; the selection is URL-addressable.

The baseline already says "every Grafana dashboard", but in practice the audit excluded
`dashboards/profiling.json`, whose panels are Pyroscope-backed rather than VictoriaMetrics-backed and
which an earlier survey reported as "0 of 0 queries".

**This change:** the requirement text is unchanged and the scope is made explicit:

> This applies to every dashboard in the repository's top-level `dashboards/` directory and to every
> kit dashboard, **with no exclusions**, `dashboards/profiling.json` included. … Profiles carry
> `cluster` with the same `clusterLabelName()` value metrics and logs use … Its variable is a
> Pyroscope one rather than a VictoriaMetrics one, so the variable's datasource follows the panels it
> scopes.

All four existing scenarios are preserved verbatim. Two are added: every dashboard in the repository
exposes the field with no exclusions, and live-cluster behaviour for a single cluster is unchanged.

**This is a tightening, not a reversal.** The baseline already demanded the variable everywhere; the
change removes the room an implementer had to read "queries all available cluster values from
VictoriaMetrics" as scoping the requirement to VictoriaMetrics-backed dashboards.

### multi-cluster-dashboards: All metric panel queries are scoped by cluster

**Currently** (`openspec/specs/multi-cluster-dashboards/spec.md:37-51`):

> Every PromQL query in a VictoriaMetrics-backed panel SHALL include `{cluster=~"$cluster"}` (or
> equivalent label selector) to scope results to the selected cluster(s).

Two scenarios: a panel query respects the cluster selection, and the system-overview hostname cascade
is cluster-scoped.

**This change:** the requirement text is unchanged and two paragraphs are appended:

> Panels backed by other datasources SHALL be scoped by that datasource's own equivalent. In
> particular, a Pyroscope panel carries its cluster filter in its `labelSelector`, not in an `expr`.
>
> The enforcement test SHALL walk `labelSelector` fields as well as `expr` fields. A test that walks
> only `expr` passes `dashboards/profiling.json` while it silently blends two clusters into one flame
> graph.

Both existing scenarios are preserved verbatim. Two are added: the enforcement test walks
`labelSelector` fields, and two clusters in one aggregate do not blend.

**Why this belongs in the spec rather than in a task.** A test that walks only `expr` reports green
for the exact dashboard that is broken. Naming the field the test must walk is the only form of this
requirement that a passing test can be trusted against.

### profiling: Completed JFR chunks are shipped to Pyroscope

**Currently** (`openspec/specs/profiling/spec.md:325-377`):

> The system SHALL ship completed JFR chunks from each node to the Pyroscope server's ingest
> endpoint, labelled with the node's hostname and the cluster name. The system SHALL only ship chunks
> that are complete, and SHALL never ship the chunk currently being written. A chunk SHALL be shipped
> at most once, and SHALL remain retrievable after it has shipped.

Eight scenarios covering shipping, exclusion of the in-flight chunk, the final chunk of a stopped
session, re-shipping, profile selectability, wall-clock sessions, and two systemd-timeout cases. The
requirement says nothing about where Pyroscope stores what it receives.

**This change:** the same requirement, with the store named:

> Pyroscope's object store SHALL be the accumulating account bucket, not the ephemeral per-cluster
> data bucket. … `down` applies a whole-bucket lifecycle expiration to the data bucket, so a profile
> stored there does not remain retrievable and the guarantee above does not hold.
>
> The account bucket's region SHALL be resolved once, at bucket-ensure time, via
> `GetBucketLocation`, stored on `ClusterState`, and exposed as its own template variable. …
>
> `BUCKET_NAME` SHALL keep its current meaning untouched. It has exactly two consumers, and the other
> is ClickHouse's S3 data disk, which SHALL NOT move to the account bucket.

All eight existing scenarios are preserved verbatim. Eight are added: the bucket is the account
bucket; profiles survive the data bucket's expiry; the region comes from `GetBucketLocation` rather
than from the cluster; four covering a `state.json` that predates the region field; and
`BUCKET_NAME` resolves unchanged so ClickHouse's data disk does not move.

**The pre-existing-`state.json` case is specified rather than left to implementation.** A cluster
provisioned before the region field existed carries a `state.json` without it, and a live shared
cluster is in exactly that position. `ClusterState.s3Bucket` is `String?` and `dataBucket` defaults
to `""` (`ClusterState.kt:216,218`), so a new field with a default deserializes cleanly on an old
file — but clean deserialization is not correct behaviour.

Of the three available answers, the change takes the middle one. Refusing and naming what it needs
would break a working cluster to fix a field the tool can resolve itself, which is disabling
functionality rather than fixing configuration. Falling back to the cluster's region is the exact
defect item 15 exists to remove, and it fails silently — a cluster inside the bucket's region works
and one outside it points at the wrong endpoint. So the region is resolved lazily on first use with
the same `GetBucketLocation` call and persisted, making it a once-per-cluster resolution rather than
a once-per-command one. Fail-fast still governs the call itself: when the resolution fails, the
command fails naming the bucket and the call, and substitutes nothing.

**This is the requirement the current implementation already fails.** "SHALL remain retrievable after
it has shipped" does not hold when the store sits in a bucket `down` expires wholesale. The change
makes the requirement's existing promise achievable rather than adding a new one.

### profiling: Local JFR retention is bounded — deliberately NOT modified

`openspec/specs/profiling/spec.md:424-462` requires node-local JFR chunks to be pruned by age and by
total size, including unshipped chunks. That reads like a conflict with this change's
no-automatic-deletion constraint, and it is not one: those bounds apply to the chunk buffer on a
database node, not to a store. Chunks are shipped to Pyroscope, and Pyroscope is the store that must
accumulate. The requirement exists so an unreachable Pyroscope cannot fill the volume Cassandra
stores data on.

Recorded here so a later reader does not mistake the omission for an oversight.

### platform-substrate / kit-install-command: `__BUCKET_NAME__` — deliberately NOT modified

Both specs document `__BUCKET_NAME__` as `ClusterState.dataBucket` (falls back to `s3Bucket`)
(`platform-substrate/spec.md:91`, `kit-install-command/spec.md:207`). This change adds a **new**
template variable for the account bucket's region and repoints Pyroscope's config at the account
bucket directly. It does not change `__BUCKET_NAME__`'s meaning, so neither spec is modified.

Changing `__BUCKET_NAME__` instead would have moved ClickHouse's S3 data disk to the account bucket
as a side effect. That is the reason for the separate variable.

No `REMOVED Requirements` sections in this change.

## Conflicts with other in-flight changes

Three other changes are open. Their delta specs were read, not judged by folder name.

### `cassandra-local-builds` — no conflict, one adjacency

Touches one capability, `cassandra-local-builds`, which is new and has no baseline file. Its five
requirements are Local build of a Cassandra checkout, Build identity, Published build layout, Builds
are discovered from the bucket, and Installing a published build. This change touches none of them,
and that change touches none of `local-observability-stack`, `cluster-lifecycle`, `observability`,
`multi-cluster-dashboards` or `profiling`.

**One adjacency worth naming, which is not a conflict.** Both changes work on the account bucket.
`cassandra-local-builds` publishes under `cassandra-builds/` — a sibling of `clusters/` — and
requires the account bucket to be created if it does not yet exist. This change adds a
`GetBucketLocation` call to that same bucket-ensure path. Two additions to one code path, in
different directions; neither constrains the other, and neither modifies a requirement the other
states. Whichever lands second rebases onto the other's version of bucket-ensure.

`cassandra-builds/` also happens to gain protection from this change: with `Down`'s cluster-prefix
lifecycle rule removed, no teardown writes any lifecycle configuration to the account bucket at all.
That is a benefit, not a dependency.

### `issue-888` — no conflict

Touches `kit-install-command` (MODIFIED: Kit descriptor filename) and `workload-runner` (MODIFIED:
Installed workload dirs are discovered as top-level subcommands, Discovered workloads appear in
--help output; ADDED: Filesystem kit discovery has a single source of truth). This change touches
neither capability, and issue-888 touches none of this change's five.

The nearest contact is the CLI command tree: issue-888 changes how filesystem kits register as
top-level subcommands, and this change registers a new static top-level group named `local`. The two
meet only if a workspace directory is named `local` and holds a kit descriptor, which the existing
collision rule already handles — the same rule `issue-892` relies on for a directory named `profile`.
Not a conflict, and not a shared requirement.

### `issue-892` — no conflict

Touches `profile-command-group` (new) and `setup` (MODIFIED: Profile Setup). This change touches
neither. Both changes add a top-level command group — `profile` there, `local` here — and both edit
`CommandLineParser`'s top-level list and `Repl.kt`'s hand-maintained copy of the command tree. That
is a textual merge in two files, not a requirement conflict: neither group's registration constrains
the other's.

`issue-892` also relocates `PicoCommand` out of `commands/` to a kernel package. The `local`
commands here are `PicoBaseCommand` subclasses, which are unaffected by that move — the 56 files
importing `PicoBaseCommand` extend the base class rather than the interface. If `issue-892` lands
first, the new commands are written against the relocated interface with no design change.
