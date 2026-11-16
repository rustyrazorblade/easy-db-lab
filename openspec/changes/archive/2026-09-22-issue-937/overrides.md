# Overrides and conflicts — issue-937

## Overrides existing behavior

None — this change only adds new requirements (the `telemetry-redirect` capability).  Redirect is a new, conditional bring-up mode; the default (non-redirect) behavior of the `observability`, `profiling`, and `live-stream-metrics` capabilities is unchanged (AC4), so no baseline requirement is modified or removed.

**For the owner's awareness** — baselines that gain a redirect-*gated* branch (default behavior untouched):

- `observability` — the OTel Collector's export destinations become configurable (in-cluster svc when local, external when redirect); the `logs/local`, `logs/otlp`, and span-derived metrics pipelines gain the `cluster` label (additive, harmless in local mode).
- `profiling` — the Pyroscope target becomes the external endpoint under redirect; local target unchanged otherwise.
- `live-stream-metrics` and the metrics/logs backup commands — these read the local backend, so they refuse cleanly on a redirect cluster; behavior on a normal cluster is unchanged.

## Conflicts with other in-flight changes

None found.  Open changes at spec time: `cassandra-local-builds` (`cassandra-local-builds`), `issue-888` (`workload-runner`, `kit-install-command`), `issue-892` (`profile-command-group`, `setup`), `issue-932` (`cli-help-topics`).  None touches the `telemetry-redirect` capability, and none modifies a requirement this change touches.

**Non-blocking proximity note:** `issue-892` edits the `setup` capability and profiling command surface, and this change edits `SetupInstance.kt` (profiling Pyroscope endpoint selection) and the profiling producers.  No requirement conflict, but the two changes touch adjacent code; whichever merges second should expect a small merge in `SetupInstance.kt` / profiling wiring.
