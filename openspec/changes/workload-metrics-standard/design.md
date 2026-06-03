## Context

Workloads with `metrics.type: scrape` already register with VictoriaMetrics via the OTel collector. The data is there but there is no tooling to discover it, no committed documentation of which metrics matter, and no dashboards built from real data. The result is that any agent or developer who wants to build a dashboard must guess at metric names — which is wrong. This change establishes a catalog-first workflow: run the export script once against a live cluster, commit the catalog, then author METRICS.md and the Grafana dashboard from that catalog.

VictoriaMetrics is always the metrics backend (port 8428 on the control node). The OTel collector always uses `job="<workload>"` as the label for workload scrape targets.

## Goals / Non-Goals

**Goals:**
- Single global `bin/export-workload-metrics` script that works for any scrape-type workload
- Machine-readable `METRICS.md` format agents can parse to understand a workload's key metrics
- Grafana dashboard JSON built from real catalog data and committed into the workload's resource templates
- Integration tests verify metrics are actually flowing (not just that the workload started)
- Workload-creation skill documents when and how to produce these artifacts

**Non-Goals:**
- Automatic dashboard generation (dashboards are authored once from real data, not generated on the fly)
- Metrics catalog refresh on every test run (catalog is refreshed manually, not CI-driven)
- Support for non-scrape workloads (java-agent, helm-native have different metric flows)

## Decisions

### Decision: One global script, not per-workload

A single `bin/export-workload-metrics <workload>` reads `env.sh` for `CONTROL_HOST_PRIVATE` and queries VictoriaMetrics at `http://$CONTROL_HOST_PRIVATE:8428/api/v1/series?match[]={job="$WORKLOAD_NAME"}`. Output is normalized JSON and saved to `<workload>/metrics-catalog.json` in the cluster working directory (same place kubeconfig, env.sh, etc. live).

Alternative considered: per-workload export scripts inside the workload directory. Rejected: every workload queries VictoriaMetrics the same way; a per-workload script is duplication.

### Decision: metrics-catalog.json format

```json
{
  "workload": "presto",
  "exported_at": "2026-05-20T14:00:00Z",
  "series": [
    {
      "name": "jvm_memory_bytes_used",
      "labels": {"job": "presto", "area": "heap"},
      "description": ""
    }
  ]
}
```

The `description` field is empty in the machine-generated catalog. METRICS.md overlays human descriptions on the subset of series that matter.

### Decision: METRICS.md is a machine-readable agent spec, not human docs

Format:
```markdown
# Presto Metrics

## Key Metrics

| Metric | Labels | Description |
|--------|--------|-------------|
| jvm_memory_bytes_used | area=heap | JVM heap used bytes |
| presto_active_drivers | | Active query drivers |
```

Plain markdown table so agents can parse it without special tooling. No prose sections — just the table.

### Decision: Dashboard JSON lives in workload resource templates

`src/main/resources/.../install/<workload>/dashboards/<workload>.json` is already picked up by `WorkloadRunnerCommand.installDashboards()` on `start`. No new installation mechanism needed.

### Decision: Test verification queries the series API, not instant query

`/api/v1/series?match[]={job="<workload>"}` returns the list of series. Counting series (≥10) is a more robust signal than querying a specific metric name (which would couple the test to the catalog).

Wait time: 30s after `start` completes before asserting. OTel collector scrapes every 15s; two scrape cycles is sufficient.

### Decision: Workload-creation skill updated with metrics guidance

The `generate-workload` skill documents that:
- Scrape-type workloads SHOULD include `metrics-catalog.json`, `METRICS.md`, and a dashboard
- These are generated once from a live cluster, not templated
- The skill explains the workflow: start workload → run export-workload-metrics → author METRICS.md → build dashboard from catalog

## Risks / Trade-offs

- [Catalog staleness] `metrics-catalog.json` is committed and manually refreshed. New metrics added by an upstream chart upgrade won't appear until someone reruns the export. → Mitigation: METRICS.md only lists key metrics; new metrics that aren't in METRICS.md still flow to VictoriaMetrics and can be queried — they just won't have a description.
- [Dashboard coupling] Dashboard JSON is built from real metric names; if upstream renames a metric, the dashboard breaks silently. → Mitigation: test verification step catches "no data" panels when the workload runs in CI.
- [30s wait in tests] Adds 30s to every integration test that has a metrics step. → Accepted: correctness matters more than test speed for integration tests.
