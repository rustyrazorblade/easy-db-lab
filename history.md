# Lab History

## Session 1 — 2026-05-28

### Goal
Validate all `easy-db-lab cassandra` subcommands against a Cassandra 5.0 3-node cluster.

### Infrastructure
- 3 DB nodes: i4i.xlarge (db0/db1/db2 across us-west-2a/b/c)
- 1 App node: i4i.xlarge (app0)
- Control node: control0

### Results

| Step | Command | Result |
|------|---------|--------|
| 1 | `easy-db-lab init --db 3 --app 1 --instance i4i.xlarge --stress-instance i4i.xlarge --up` | PASS — cluster provisioned, full observability stack running |
| 2 | `cassandra list` | PASS — 5.0 in list |
| 3 | `cassandra use 5.0` | PASS — Java 11 selected, config deployed to all 3 nodes |
| 4 | `cassandra write-config test.patch.yaml` | PASS — file created |
| 5 | `cassandra download-config` | PASS — JVM and YAML files downloaded locally |
| 6 | `cassandra update-config cassandra.patch.yaml` | PASS — config pushed to all 3 nodes |
| 7 | `cassandra start` | PASS — all 3 nodes started sequentially, each confirmed NORMAL |
| 8 | `cassandra nt status` | PASS — all 3 UN (db0/db1/db2) |
| 9 | `cassandra nt ring` | PASS — all 3 nodes present, 4 tokens each, Up/Normal |
| 10 | `cassandra nt compactionstats` | PASS — 0 pending tasks |
| 11 | `cassandra nt tpstats` | PASS — 0 dropped messages |
| 12 | `cassandra cql "SELECT release_version FROM system.local"` | PASS — returns `5.0.8` |
| 13 | `cassandra stress list` | PASS — 16 workloads listed |
| 14 | `cassandra stress fields` | PASS — 6 generators listed |
| 15 | `cassandra stress info KeyValue` | PASS — schema and params shown |
| 16 | `cassandra stress start --name cassandra5-validation --tags ... -- KeyValue -d 5m --threads 50` | PASS — **NOTE: `--` separator required before stress args** |
| 17 | `cassandra stress status` | PASS — job shown as Running |
| 18 | `cassandra stress logs cassandra5-validation` | PASS — ~530 writes/s, ~523 reads/s, 0 errors |
| 19 | Waited for completion | PASS — job Completed 1/1 |
| 20 | `cassandra stress stop --all --force` | PASS |
| 21 | `cassandra restart` | PASS — all 3 nodes restarted, returned UN; sidecar restarted |
| 21b | `cassandra nt proxyhistograms` | PASS — p50 read ~2.3ms, p99 ~17ms |
| 22 | `cassandra stop` | PASS — all nodes stopped, sidecar DaemonSet deleted |

### Known Quirks
- **`cassandra stress start` requires `--` before workload args.** Running `stress start --name foo KeyValue -d 5m --threads 50` fails because `-d` and `--threads` are parsed as options for `start`. Correct form: `stress start --name foo -- KeyValue -d 5m --threads 50`
- **axonops sudoers warning** appears on every `update-config` call — harmless, can be ignored.
- **nodetool proxyhistograms write latency resets to 0 after restart** (expected — histograms are in-memory).

### Overall
All 23 plan steps completed successfully. Cassandra 5.0.8 provisions correctly, nodetool works on all nodes, CQL access works, stress subcommands work. Cluster is solid.

### Teardown
Cluster torn down with `easy-db-lab down`.
