# Overrides and conflicts

## Overrides existing behavior

### cluster-lifecycle: Cluster Teardown

**Currently:** "The system MUST clean up all AWS resources on cluster teardown. Data bucket cleanup MUST use lifecycle expiration rather than individual object deletion." Teardown has no backup step; it proceeds straight to resource cleanup after confirmation.

**This change:** Adds a mandatory automatic backup (VictoriaMetrics + Grafana annotations, coupled) that runs BEFORE any infrastructure is torn down. A backup failure (after retry) aborts teardown with no infrastructure removed and reports the failure. A new `--force` flag skips the backup and proceeds. The existing resource-cleanup and lifecycle-expiration behavior is unchanged; the backup gate is added ahead of it.

## Conflicts with other in-flight changes

None found. The other open changes touch `cassandra-local-builds`, `kit-install-command`, `workload-runner`, `profile-command-group`, `setup`, and `cli-help-topics`. None touches `cluster-lifecycle` or the new `grafana-annotations` capability.
