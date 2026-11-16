## Context

Issue 939 adds three things that serve one goal: make A/B config-change markers survive an ephemeral cluster's teardown. The design was decided with the owner during activate; this section records the choices and, importantly, the reversal from the original grooming.

The original grooming scoped a whole-`grafana.db` backup with import/restore. The owner reversed that during the design stop. The whole DB carries dashboards, datasources, and Grafana's own accounts table; the owner needs none of them, because dashboards and datasources regenerate from code on every fresh cluster. Annotations are the only work product worth preserving. So the backup captures annotations only, over the Grafana HTTP API, and import/restore is deferred to a follow-up issue.

## Goals / Non-Goals

Goals:
- A `grafana annotate` command that drops a human-readable, tagged, optionally scoped marker on the timeline.
- Global markers that actually render on the core dashboards.
- An annotations backup that is never lost and never blocks nothing important.
- An automatic backup at `down` that protects metrics and annotations as critical work product.

Non-Goals:
- Import / restore of annotations (fresh cluster or central Grafana). Deferred.
- Whole-`grafana.db` backup.
- Logs and Tempo at teardown.
- In-CLI event-driven auto-annotation (issue 918).

## Decisions

### D1 — Annotations only, over HTTP (not the whole DB)

Back up annotations with `GET /api/annotations`, restore-side with `POST /api/annotations` (the restore side is deferred). This is symmetric with `grafana annotate` and reuses `GrafanaDashboardService`'s injected `OkHttpClient`. It removes every hazard of a whole-DB backup at once: live-SQLite consistency, WAL sidecar handling, an `sqlite3` Job image, scale-to-0 restore choreography, and org/UID drift on a wholesale DB replace.

### D2 — Account-level backup location

All backups go to an account-level S3 location, keyed by cluster name and timestamp, outside the per-cluster prefix that `Down.setClusterLifecycleRule()` expires (default 1 day). This guarantees a backup is never lost to teardown expiration and is findable after the cluster is gone. This is a hard requirement, not a preference: backups are critical work product.

### D3 — Backup runs first at `down`; a failure aborts teardown

At `down`, the coupled metrics + annotations backup runs first, before any infrastructure is torn down. If it fails after retry, `down` aborts and removes no infrastructure. Because the backup runs before any destruction, a failure leaves the whole cluster intact — there is no partial-teardown trap on the normal path. The `--force` flag skips the backup and proceeds. This deliberately overrides the usual "never block teardown" instinct: the owner prefers a still-billing cluster over lost data.

### D4 — Coupling is atomic in attempt, not in artifact

The metrics and annotations backups are always attempted together — never one without the other invoked. Each is retried. The coupling lives at one call site (a `TeardownBackupService`), which keeps `Down` thin and gives the coupling one testable home.

### D5 — `annotate` fails non-zero when Grafana is unreachable

`grafana annotate` throws (non-zero exit) on an unreachable API, rather than following the `MetricsImport` pattern of emitting a failure event and returning 0. This is a deliberate deviation from that sibling, required by the acceptance criterion.

### D6 — Provision a tag-based annotation query on the core dashboards

Grafana renders an annotation on a dashboard only where the dashboard has a matching annotation query. Global tag-based annotations therefore need a provisioned, tag-filtered annotation query on the core dashboards. This change is applied through the `dashboard-editor` agent (edit → deploy → read back from Grafana), per repo rules.

## Alternatives Considered

- **Whole-`grafana.db` backup (original grooming, recommended by the architect).** Rejected by the owner: it drags in dashboards, datasources, and the accounts table that regenerate from code or are unneeded, and it carries live-SQLite consistency, WAL, `sqlite3`-image, and scale-to-0 restore hazards (design-critic M1/M2/M3). This was an explicit owner override of the architect's recommendation, made after seeing what the whole DB contains.
- **`VACUUM INTO` vs SQLite online-backup vs scale-to-0 (architect's D1 options).** All moot under the annotations-only decision; there is no SQLite file to snapshot.
- **Best-effort coupling at `down` — keep whatever backup succeeds (architect's D3 recommendation).** Rejected: the design-critic showed it contradicts "never one without the other", and the owner requires a failed backup to block teardown outright, not to proceed with a partial set.
- **Never block teardown / exception-isolate the backup (design-critic C3).** Deliberately overridden by the owner: a failed backup must block `down`. The `--force` flag is the sole escape.
- **Import into a central Grafana as part of 939.** Deferred to a follow-up issue at the owner's direction; the annotations-only artifact makes a later merge/append import natural.

## Risks / Trade-offs

- **`down` can now fail on purpose.** A backup failure leaves a still-billing cluster up. This is intended; `--force` is the documented escape. The teardown-path metrics backup Job SHOULD use a short timeout so a stuck backup does not delay the abort/`--force` decision (the standalone path may keep the longer default).
- **Global-annotation rendering depends on the provisioned query.** If the tag-filtered query is missing or misconfigured, markers exist but do not render. The `dashboard-editor` read-back step guards this.
- **Account-level S3 growth.** Annotations JSON is tiny, but the account-level location is never expired by cluster teardown. Acceptable; annotations are small and are the work product the owner wants kept.

## Follow-up (separate issues)

- Import / restore of annotations — into a fresh ephemeral cluster and, by merge/append, into a central long-lived Grafana (default local target, `--target <url>` for central, dedup on re-import). Deferred from 939.
- `VictoriaBackupService.waitForJobCompletion` hand-rolls a `while` + `Thread.sleep` loop; the house rule is resilience4j. Not folded in — refactoring it is not small. Recommend a separate issue (design-critic / architect flag).
- `ClusterStateManager` uses Jackson vs the kotlinx.serialization rule. Untouched here. Separate issue.
