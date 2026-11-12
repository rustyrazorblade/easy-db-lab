## Overrides existing behavior

- `dynamic-otel-scrape-config`: `yace` is no longer one of the static scrape jobs; the builder renders it where CloudWatch exists.
- `tool-execution` "Journald log collection pipeline" says the journald collector is deployed on all nodes. It is now deployed only where the environment has a systemd journal. This change rewrites the requirement (MODIFIED delta in `specs/tool-execution`), which also corrects its stale `otel-journald` wording.

## Conflicts with other in-flight changes

- `issue-1034` (PR #1078): no overlap.
- #1035 (observability storage, not started): its `ObservabilityStorage.from(ClusterState)` follows the same pattern. D2 keeps the two independent.
