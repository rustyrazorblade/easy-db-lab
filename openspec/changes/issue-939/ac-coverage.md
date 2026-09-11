# Acceptance-criteria coverage

| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC | annotate with no --time creates an annotation at now, with confirmation identifying it | `grafana-annotations: Annotate at the current time with no explicit time` | ✅ Covered |
| AC | annotate with explicit time + tags (and other fields) creates it at that time carrying them | `grafana-annotations: Annotate with an explicit time and tags` | ✅ Covered |
| AC | a global tagged annotation renders on the core dashboards | `grafana-annotations: A tagged global annotation appears on a core dashboard` | ✅ Covered |
| AC | annotate against a supported scope (global vs dashboard/panel) lands in that scope | `grafana-annotations: Annotate a specific dashboard or panel scope` | ✅ Covered |
| AC | unreachable Grafana → clear error naming the endpoint, non-zero exit, no silent success | `grafana-annotations: Unreachable Grafana produces a non-zero exit` | ✅ Covered |
| AC | grafana backup on UP + S3 → annotations captured to an account-level location, URI reported | `grafana-annotations: Backup uploads annotations and reports the URI` | ✅ Covered |
| AC | grafana backup with no S3 bucket → fails fast with the "run up first" message | `grafana-annotations: Backup fails fast with no S3 bucket` | ✅ Covered |
| AC | down auto-backup runs before any teardown; metrics + annotations always together | `cluster-lifecycle: Automatic backup runs before any infrastructure is torn down` | ✅ Covered |
| AC | on backup failure, down aborts and removes no infrastructure, reporting the failure | `cluster-lifecycle: Backup failure aborts teardown with no infrastructure removed` | ✅ Covered |
| AC | down --force skips the backup and tears down anyway | `cluster-lifecycle: --force skips the backup and tears down anyway` | ✅ Covered |
| Risk | down can now fail on purpose, leaving a still-billing cluster up | `cluster-lifecycle: Backup failure aborts teardown with no infrastructure removed` (intended behavior; `--force` is the escape) | ✅ Covered |
| Risk | global annotations do not render without a provisioned query (critic H2) | `grafana-annotations: A tagged global annotation appears on a core dashboard` (D6 provisions the query; dashboard-editor read-back guards it) | ✅ Covered |
| Risk | backup lost to per-cluster teardown expiration (critic C1) | `grafana-annotations: Backup uploads annotations and reports the URI` (account-level location, D2) | ✅ Covered |
| Risk | annotate silently returns 0 on failure like MetricsImport (critic H3) | `grafana-annotations: Unreachable Grafana produces a non-zero exit` (D5) | ✅ Covered |
| Risk | teardown-path backup Job hangs on the 600s default, delaying the abort/--force decision (critic H1) | tasks.md 5.2 (short teardown-path timeout) | ⚠️ Excluded — an implementation constraint (Job timeout tuning), not an externally observable behavior; captured as a task, not a scenario |
| Excluded | AC8 (fresh cluster + restore → annotations present) | — | ⚠️ Excluded — import/restore deferred to a follow-up issue per the owner's activate decision |
