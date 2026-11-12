## Why

Part of #1032 (local execution environment). On a local K3s-in-docker node, fluent-bit-journald mounts `/var/log/journal`, which the node does not have, so the rollout wait times out and `up` fails (spike break B10). YACE and s3manager have nothing to do there: there is no CloudWatch or S3. Epic decision Q4: each environment declares what its nodes provide.

Owner decision, 2026-11-08: this issue is the capability gating only. The other two host-path couplings named in the original issue are not capabilities. They are AMI couplings removed on every environment, and they move to #1079 (ebpf-exporter programs in an image) and #1080 (registry TLS without S3 or a host path).

## What Changes

- New `NodeCapability` (`SYSTEMD_JOURNAL`, `CLOUDWATCH`, `S3`), and `NodeCapabilities.from(ClusterState)`, which returns the set the #1040 execution environment declares. AWS declares all three and local Docker declares none. Until #1040 records the environment, `from` returns AWS. No consumer tests an environment name.
- `ObservabilityStackService` stages declare the capability they need. A stage whose capability is absent is not built, is not awaited, and emits `Event.Grafana.StageSkipped(stage, capability, reason)`. The manifest builders do not change.
  - fluent-bit-journald needs `SYSTEMD_JOURNAL`.
  - YACE needs `CLOUDWATCH`.
  - s3manager needs `S3`.
- The `yace` scrape job moves out of the static `otel-collector-config.yaml` into a builder function gated on `CLOUDWATCH`, like `buildCniScrapeJobs`. The deploy path and the kit-sync path (`OtelSyncService`) render the same collector config.
- `status` (`Status.kt`, `StatusCache.kt`) shows the S3 Manager section only with `S3`.
- Docs:
  - `configuration/CLAUDE.md` and the root `CLAUDE.md` observability section (the collectors list, and which workloads are gated).
  - `events/CLAUDE.md` for the new event.
  - Spec deltas for `cloudwatch-metrics-export` and `dynamic-otel-scrape-config`.

## Capabilities

### New Capabilities

- `node-capabilities`: each environment declares its node capabilities, and a workload that needs an absent one is skipped and reported.

### Modified Capabilities

- `cloudwatch-metrics-export`: YACE runs only where CloudWatch exists.
- `dynamic-otel-scrape-config`: `yace` is no longer a static scrape job.

## Impact

- On AWS: no behaviour change. The same workloads are applied and the collector config is the same (the `yace` job is now rendered by the builder).
- Locally, three workloads are skipped and reported. Pod logs still reach Loki through the collector's existing `file_log/containers` pipeline.
- #1041 (local up/down) still needs #1079 and #1080 before every workload is Ready locally.

## Out of scope

- The eBPF objects (#1079).
- Registry TLS (#1080).
- A custom node image with a journal.
- Probing capabilities per node (owner chose declared per environment).
- Deriving `S3` from #1035's storage choice (owner chose a separate capability).
- Recording the environment in state (#1040).
- Future capabilities a bring-your-own Kubernetes environment will need: `PRIVILEGED_NODE_AGENTS` (ebpf, Beyla), `HOST_PATH` (Fluent Bit and collector log mounts) and `NODE_SSH`. They are not added now, because no second implementation exists. The enum is not final.
