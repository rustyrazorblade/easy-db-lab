| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC | Mimir writes to `mimir/`, Loki to `loki/`, Tempo to `tempo/`, Pyroscope to `pyroscope/`; `down` writes annotation backups to `grafana/annotations/<tenant>/` | `observability-store: Metric blocks location`, `Log chunks and index location`, `Trace blocks location`, `Profiles location`, `Annotations backup location`, `Default tenant location` | ✅ Covered |
| AC | Cluster role denied deletes under `mimir/`, `loki/`, `tempo/`, `grafana/`; allowed under `pyroscope/` | `observability-store: A backend delete is denied`, `Pyroscope compaction still works`, `The deny is re-applied on up` | ✅ Covered |
| Owner decision | Every reference to the former prefixes is removed | `observability-store: The former prefixes are gone` | ✅ Covered |
| AC | `down` without `--force`: mirror first, then the flushes, the Pyroscope check and the annotations backup in parallel, then teardown | `cluster-lifecycle: The tail of every signal reaches S3 before teardown`, `The Phase B steps run in parallel` | ✅ Covered |
| AC | Every span Tempo received is in `tempo/` before teardown | `cluster-lifecycle: The tail of every signal reaches S3 before teardown`, `The Tempo drain waits for traces still in memory` | ✅ Covered |
| AC | When steps fail: every step still finishes, nothing removed, no backend restarted, every failed signal named with `--force` | `cluster-lifecycle: One failed signal does not stop the others`, `Every failed signal is reported`, `A failed mirror fails only the logs signal` | ✅ Covered (a failed mirror skips the Loki flush by owner decision D2) |
| AC | `down --force` lists the signals it did not save before teardown starts | `cluster-lifecycle: --force lists what it will not save before the prompt` | ✅ Covered (shown before the confirmation prompt, by owner decision) |
| AC | Mimir uploads a sample's block within about 3 minutes | `observability-store: Metric blocks reach S3 within minutes` | ✅ Covered |
| AC | Tempo uploads a span's block within about 2 minutes | `observability-store: Trace blocks reach S3 within minutes` | ✅ Covered |
| AC | Loki uploads a continuously written stream's chunk within about 15 minutes, index at the next rotation | `observability-store: Log chunks reach S3 within minutes` | ✅ Covered |
| AC | The ABSOLUTE RULE in root `CLAUDE.md` carries the "compaction is not deletion" text | — | ⚠️ Excluded — contributor guidance, not product behavior; task 8.1 writes it verbatim |
| Owner decision | Pyroscope needs no flush and is reported as such | `cluster-lifecycle: Profiles need no flush` | ✅ Covered |
| Owner decision | Record logs and metrics the moment each finishes; a re-run skips them and repeats the rest | `cluster-lifecycle: A signal is recorded when its flush finishes`, `A re-run skips the recorded signals` | ✅ Covered |
| Owner decision | The collector stop succeeds when it is already gone | `cluster-lifecycle: The collector stop succeeds when the collector is gone` | ✅ Covered |
| Critic 5 | A re-run after a stopped Loki reports the real cause, not a mirror failure | `cluster-lifecycle: A re-run after a stopped Loki reports the real cause` | ✅ Covered |
| Other capabilities | CloudWatch metrics and the `grafana backup` artifact use the new roots | `cloudwatch-metrics-export: Metrics survive cluster teardown`, `grafana-annotations: Backup uploads annotations and reports the URI` | ✅ Covered |
| Risk | Parallel steps race in the SSH connection cache | — | ⚠️ Excluded — internal thread safety, not observable behavior; task 3.1 with a concurrency test |
| Risk | A state write during the parallel phase is read half-written | `cluster-lifecycle: A signal is recorded when its flush finishes` | ✅ Covered (atomic write; task 3.2 test) |
| Risk | A single live-traces reading of 0 is stale | `cluster-lifecycle: The Tempo drain waits for traces still in memory` | ✅ Covered |
| Risk | Mimir 1-minute blocks grow local disk, memory and S3 objects | — | ⚠️ Excluded — accepted by owner (D7); local-retention cut and compaction are #970 |
| Risk | Loki's out-of-order window shrinks to 7.5 minutes | — | ⚠️ Excluded — accepted by owner; documented in task 8.4 |
| Risk | A redirect target's Tempo cannot drain while a source DC sends | — | ⚠️ Excluded — owner: no failure-case handling |
| Risk | The collector stays deleted after a failed `down` | — | ⚠️ Excluded — owner: no restore |
| Risk | S3 refuses uploads during the save | — | ⚠️ Excluded — owner directive: S3 is treated as reliable |
