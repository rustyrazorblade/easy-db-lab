## Overrides existing behavior

- `dynamic-otel-scrape-config`: `yace` is no longer one of the static scrape jobs; the builder renders it where CloudWatch exists.
- `tool-execution` "Journald log collection pipeline" says the journald collector is deployed on all nodes. It is now deployed only where the environment has a systemd journal. That requirement is already stale (it describes `otel-journald` in chroot mode, not Fluent Bit). Correcting it is left to a spec cleanup, not this change.

## Conflicts with other in-flight changes

- `issue-1034` (PR #1078): no overlap.
- #1035 (observability storage, not started): its `ObservabilityStorage.from(ClusterState)` follows the same pattern. D2 keeps the two independent.
