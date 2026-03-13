## Context

The 11 Grafana dashboard JSON files live in `src/main/resources/com/rustyrazorblade/easydblab/configuration/grafana/dashboards/`:

- `cassandra-overview.json` — main Cassandra metrics dashboard
- `cassandra-condensed.json` — compact Cassandra view
- `clickhouse.json` — ClickHouse metrics
- `clickhouse-logs.json` — ClickHouse log analysis
- `opensearch.json` — OpenSearch metrics
- `emr.json` — Spark/EMR job metrics
- `profiling.json` — continuous profiling via Pyroscope
- `stress.json` — load test progress and throughput
- `system-overview.json` — host-level metrics (CPU, memory, disk, network)
- `s3-cloudwatch.json` — S3 CloudWatch metrics via YACE
- `log-investigation.json` — VictoriaLogs log exploration

All dashboards use standard Grafana template variables (`type: "datasource"` and `type: "query"`) with `label_values()` for dynamic discovery. The default datasource name is `VictoriaMetrics`; Grafana users can override this via the datasource dropdown at runtime. No custom substitution is needed before publishing.

The project already uses `softprops/action-gh-release@v2` in `nightly-cassandra-build.yml`. The `publish-container.yml` workflow already triggers on `v*` tags. GitHub App permissions do not allow writing to `.github/workflows/` files — the workflow must be created manually by a human.

## Goals / Non-Goals

**Goals:**
- Publish all dashboard JSON files as a single zip artifact on every versioned release
- Make the artifact consumable by external Grafana instances without any easy-db-lab tooling
- Keep the workflow simple and maintainable

**Non-Goals:**
- Pre-substituting VictoriaMetrics datasource names or any other deployment-specific values
- Publishing individual JSON files (a single zip is sufficient and simpler to consume)
- Triggering on non-version events (nightly builds, PRs, pushes to main)
- Adding a dashboard import CLI command to easy-db-lab

## Decisions

### Trigger on version tags only (`v*`)

Dashboard releases should be tied to versioned releases, not nightly builds. This keeps the release asset stable and correlates with the container image release. A `workflow_dispatch` is also included for manual testing.

Alternative: Trigger on every main branch push. Rejected — would create too many release assets and decouple dashboards from versioned releases.

### Single zip archive (`easy-db-lab-dashboards.zip`)

One archive is simpler to link to in documentation and easier to distribute. Grafana supports bulk import via zip in newer versions, and individual files can be extracted from the zip manually.

Alternative: Publish each JSON as a separate release asset. Rejected — 11 individual files clutter the release page and provide no benefit.

### No datasource substitution before publishing

The dashboards use Grafana's built-in datasource variable picker (`type: "datasource"`). External users select their own datasource at runtime via the Grafana UI. Publishing raw JSON without substitution keeps the artifact generic and avoids coupling to a specific deployment.

### Use `softprops/action-gh-release@v2`

Already in use in the nightly build workflow. Handles both creating new releases and attaching assets to existing ones, which means the dashboard zip can be added to the same release that the container publishing workflow creates.

### Workflow file requires manual creation

The GitHub App used by Claude Code does not have the `workflows` scope and cannot write to `.github/workflows/`. The workflow file content is specified in `tasks.md` for a human to create.

## Risks / Trade-offs

- **Datasource name coupling** — The dashboard JSON files hardcode `"VictoriaMetrics"` as the default datasource name. External users running Prometheus or a differently-named datasource must update this after import. Mitigation: document the datasource requirement clearly.
- **Dashboard staleness** — Published zip artifacts are point-in-time snapshots. Users must re-download when dashboards are updated in new releases. Mitigation: stable GitHub Release URL pattern makes automation straightforward.
