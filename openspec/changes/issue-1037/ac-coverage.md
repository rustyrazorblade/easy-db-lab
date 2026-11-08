| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC1 | Locally, every deployed workload is Ready and the rollout wait finishes | `node-capabilities: Local deploy skips the three workloads` (no wait for skipped stages) | ⚠️ Partial: ebpf-exporter and the registry still fail locally until #1079 and #1080. The full proof is #1041's end-to-end test. |
| AC2 | Locally, YACE and s3manager are absent and `up` says why | `node-capabilities: Local deploy skips the three workloads`; `cloudwatch-metrics-export: YACE skipped without CloudWatch`; `node-capabilities: s3manager is not advertised without S3` | ✅ |
| AC3 | ebpf-exporter without `/usr/local/lib/ebpf_exporter` | — | ➡️ Moved to #1079 (owner split, 2026-11-08) |
| AC4 | Registry without `/opt/registry/certs` | — | ➡️ Moved to #1080 (owner split, 2026-11-08) |
| AC5 | A local node with no journal still gets pod logs into Loki | `node-capabilities: Pod logs reach Loki without a journal` | ✅ |
| AC6 | AWS deploys every workload deployed today | `node-capabilities: AWS deploy is unchanged`; `cloudwatch-metrics-export: YACE deployed on AWS`; `dynamic-otel-scrape-config: YACE scrape job only with CloudWatch` | ✅ |
| Scope | Each environment declares its capabilities | `node-capabilities: AWS provides every capability`; `Local Docker provides none` | ✅ |
| Risk | A skipped stage is still awaited | `node-capabilities: Local deploy skips the three workloads` | ✅ |
| Risk | Deploy and kit sync render different collector configs | `dynamic-otel-scrape-config: YACE scrape job only with CloudWatch` (task 3.1 compares both paths) | ✅ |
| Risk | YACE scrape job reports `up=0` locally | `dynamic-otel-scrape-config: YACE scrape job only with CloudWatch` | ✅ |
