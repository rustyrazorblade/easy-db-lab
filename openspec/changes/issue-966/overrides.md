## Overrides existing behavior

### observability-store: Observability data lands in one layout in the account bucket
**Currently:** Mimir uses the prefix `observabilitymetrics`; Loki `observability/logs` (index under `observability/logs/index/`); Tempo `observability/traces`; Pyroscope `observability/profiles`.  Scenarios place blocks under `observabilitymetrics/acme/`, `observability/logs/acme/`, `observability/traces/acme/`, `observability/profiles/`, and the default tenant under `observabilitymetrics/default/`.
**This change:** each root sits at the top level, named for the tool: `mimir/`, `loki/` (index under `loki/index/`), `tempo/`, `pyroscope/`, and `grafana/annotations/<tenant>/` for the annotations backup.  No code, configuration or document names the former prefixes.  Adds the scenarios "Annotations backup location" and "The former prefixes are gone".

### observability-store: The cluster cannot delete observability objects
**Currently:** the instance role denies deletes on the metrics, logs, traces and annotations prefixes (`observabilitymetrics/`, `observability/logs/`, `observability/traces/`, `observability/annotations/`); profiles excluded.
**This change:** the deny covers `mimir/`, `loki/`, `tempo/` and `grafana/`; `pyroscope/` excluded.  Same rule, new prefixes.

### observability-store: Data reaches S3 while the cluster is up and survives a restart
**Currently:** Mimir ships 2-hour blocks within about a minute of cutting them; Loki flushes chunks by their age; Tempo cuts a block at most every 5 minutes.  Includes the scenario "Cause of the missing Tempo blocks is recorded" (issue 967).
**This change:** Mimir cuts 1-minute blocks with 15-second compaction and ship checks and a 2-minute idle compaction; Tempo cuts a block at most every minute; Loki flushes a chunk at 15 minutes and re-lists its index every minute.  Scenarios assert upload within about 3 minutes (metrics), 2 minutes (traces) and 15 minutes (log chunks).  The issue-967 diagnosis scenario is dropped: that work is done and recorded on issue 967.

### cluster-lifecycle: Cluster Teardown
**Currently:** a sequential save: mirror, Loki flush, Mimir flush, annotations backup; the first failure stops `down` there; one all-or-nothing flush record, and a `down` that finds it skips the whole flush; if Loki or Mimir is stopped with no record, `down` stops before the flush; `--force` skips the steps; Tempo's tail is not saved.
**This change:** Phase A (Loki running check, mirror, collector stop) then Phase B in parallel (Loki flush, Mimir flush, Tempo drain, profiles report, annotations backup); every Phase B step finishes and every failure is reported together; a failed mirror fails only logs and leaves Loki running; only logs and metrics are recorded, each the moment its flush succeeds, through one writer with atomic state writes; the other steps run on every `down`; the Tempo drain saves every received span without stopping Tempo; `--force` lists the unsaved signals with the preview, before the prompt.  The scenarios "A flush that cannot reach S3 times out and stops down" and "A failed flush step stops down" (Mimir S3 check failing after Loki was scaled to 0) are removed, per the owner directive to treat S3 as reliable; "A re-run after a successful flush skips the flush" becomes "A re-run skips the recorded signals"; "A re-run after a failed flush with a stopped backend stops" becomes "A re-run after a stopped Loki reports the real cause".  "Teardown sets no expiry rule" names the new roots.

### cloudwatch-metrics-export: CloudWatch metrics scraped into Mimir
**Currently:** after `down`, the CloudWatch-sourced metrics are in Mimir blocks under `observabilitymetrics/<tenant>/`.
**This change:** under `mimir/<tenant>/`.

### grafana-annotations: Back up Grafana annotations to an account-level S3 location
**Currently:** `grafana backup` uploads to `observability/annotations/<tenant>/`.
**This change:** to `grafana/annotations/<tenant>/`.

## Conflicts with other in-flight changes

None found.  No other change is open under `openspec/changes/`.
