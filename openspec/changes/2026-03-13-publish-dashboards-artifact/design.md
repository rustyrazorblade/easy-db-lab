## Context

The dashboard JSON files live at:
```
src/main/resources/com/rustyrazorblade/easydblab/configuration/grafana/dashboards/
```

There are currently 11 dashboards:
- `cassandra-condensed.json`
- `cassandra-overview.json`
- `clickhouse-logs.json`
- `clickhouse.json`
- `emr.json`
- `log-investigation.json`
- `opensearch.json`
- `profiling.json`
- `s3-cloudwatch.json`
- `stress.json`
- `system-overview.json`

Each dashboard JSON contains `__KEY__` template placeholders (e.g., `__CONTROL_NODE_IP__`, `__BUCKET_NAME__`) that the `TemplateService` substitutes at cluster deploy time. The published artifact will ship the raw templates — consumers are responsible for substitution.

The existing release workflow uses `softprops/action-gh-release@v2` for uploading release assets (see `nightly-cassandra-build.yml`). The same action will be used here.

## Goals / Non-Goals

**Goals:**
- Publish all dashboard JSON files as a single zip archive on every versioned release (`v*` tag).
- Keep the workflow self-contained and simple — no build step, no Kotlin compilation.
- Use the same GitHub Actions action versions already in use (`actions/checkout@v6`, `softprops/action-gh-release@v2`).

**Non-Goals:**
- Pre-processing or substituting template variables in the published dashboards.
- Publishing dashboards on non-release triggers (e.g., every push to main, nightly).
- Adding a separate `latest-dashboards` release tag that always points to HEAD.
- Changing how dashboards are provisioned inside easy-db-lab clusters.

## Decisions

### 1. Trigger: version tags only (`v*`)

**Rationale:** Dashboards are released in lockstep with the tool. A versioned release tag is the appropriate signal that a stable snapshot is ready for external consumption. Nightly or per-commit publishing would produce too much noise for consumers.

**Alternative considered:** Triggering on every push to `main` with a rolling `latest-dashboards` release tag. Rejected — consumers want stable, versioned artifacts, not a moving target.

### 2. Single zip archive, not individual files

**Rationale:** A single zip is easier to reference in documentation and simpler to download programmatically. All dashboards together form a coherent set — partial imports are rarely useful.

**Alternative considered:** Uploading each JSON file as a separate release asset. Rejected — clutters the release page and complicates download instructions.

### 3. No transformation of template variables

**Rationale:** The `TemplateService` substitution is context-specific (requires live cluster IP addresses, bucket names, etc.). The published artifact is a template that consumers adapt to their environment.

**Documentation note:** The README/docs should clearly state that `__KEY__` placeholders must be substituted before import.

### 4. Workflow file: `.github/workflows/publish-dashboards.yml`

**Rationale:** Separate workflow from existing CI checks and nightly builds. Clear, single-purpose file name.

## Risks / Trade-offs

- **Template variable confusion**: Consumers may import dashboards without substituting placeholders, leading to broken panels. → Mitigated by documentation.
- **Dashboard directory path changes**: If the dashboard source directory is ever relocated, the workflow `run` step must be updated. → Low risk; this path has been stable.
- **Workflow permissions**: The workflow requires `contents: write` to upload release assets. This is standard for release workflows and already granted in `nightly-cassandra-build.yml`.
