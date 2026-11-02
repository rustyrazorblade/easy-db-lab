# Acceptance criteria coverage for issue-1043

Notes:
- The AC "Redis lost mid-stream" states the owner's wording: 3 attempts, 1 s then 2 s apart (D10).
- The AC for Cassandra build switches uses the 30-second age limit (Q12).  The 5-minute exception is removed.

| Source | Requirement | Covering scenario(s) | Status |
|---|---|---|---|
| AC | `--metrics node.cpu,cassandra.read_p99`: one `node.cpu` per reporting host (`entity` = alias, e.g. `db0`), and `cluster.min` + `cluster.max` for `cassandra.read_p99` | live-stream-metrics: Chosen metrics are published every cycle; live-stream-metrics: Min and max across nodes | ✅ Covered |
| AC | Nodes reporting p99 0 are ignored by `cluster.min`; all 0 gives no `cluster.min`; a new cycle every 5 s | live-stream-metrics: Idle nodes are left out of the minimum; live-stream-metrics: Every node reports 0; live-stream-metrics: A new cycle starts every 5 seconds | ✅ Covered |
| AC | No `--metrics`: the 11 defaults; `node.memory`, `cassandra.compaction_completed`, `cassandra.compaction_bytes_written` not streamed | live-stream-metrics: Default names are streamed | ✅ Covered |
| AC | `--metrics` names a non-default metric (`node.memory`): it is streamed | live-stream-metrics: A non-default name can be chosen | ✅ Covered |
| AC | Unknown or empty name (`node.cpu,,x`): each bad entry named, non-zero exit before the first cycle | live-stream-metrics: Unknown and empty names are refused | ✅ Covered |
| AC | Repeated name: queried and published once per cycle | live-stream-metrics: A repeated name is used once | ✅ Covered |
| AC | Several disks or interfaces: one sample per host, summed over whole disks or `ens*`/`eth*` only | live-stream-metrics: Several disks give one sample; live-stream-metrics: Several interfaces give one sample | ✅ Covered |
| AC | `/mnt/db1`: one `node.filesystem_used` sample for that mount; no mount, no sample | live-stream-metrics: Filesystem use covers only the data mount; live-stream-metrics: Host without the data mount | ✅ Covered |
| AC | No value, NaN or Inf: no sample, not a failure, no 0 | live-stream-metrics: Empty result; live-stream-metrics: NaN or Inf value | ✅ Covered |
| AC | Cassandra not running: names accepted, nothing published; stops or `cassandra use`: gauges drop old series within the age limit; starts mid-stream: publish from the next cycle, no restart | live-stream-metrics: Cassandra is not running; live-stream-metrics: Cassandra stops; live-stream-metrics: Cassandra switches builds; live-stream-metrics: Cassandra starts mid-stream; live-stream-metrics: Stale sample next to a fresh one | ✅ Covered |
| AC | One metric's query fails (no response, non-200, body not `success`): others still published; failed one retried next cycle | live-stream-metrics: One failed query does not block the others; live-stream-metrics: Each kind of failure counts | ✅ Covered |
| AC | One failure event naming metric and reason; nothing more while failing; one recovery event | live-stream-metrics: Failure reported once; live-stream-metrics: Recovery reported once | ✅ Covered |
| AC | One terminal line per cycle with samples published and metrics failed; samples not printed | live-stream-metrics: One line per cycle; event-system: Sample is not printed | ✅ Covered |
| AC | `EASY_DB_LAB_REDIS_URL` not set: non-zero before the first cycle, message names the variable | live-stream-metrics: Redis URL not set | ✅ Covered |
| AC | Redis does not answer at start: non-zero before the first cycle, message names the variable and URL | live-stream-metrics: Redis does not answer at start; event-system: Fail fast when Redis unavailable | ✅ Covered |
| AC | Redis lost mid-stream: found within one cycle (per-cycle ping), reconnect with `createNetworkRetryConfig` (3 attempts, 1 s then 2 s), samples dropped, continue on success, exit naming URL after 3 failures | live-stream-metrics: Loss found within one cycle; live-stream-metrics: Reconnect succeeds; live-stream-metrics: Reconnect fails; live-stream-metrics: Stop during a reconnect | ✅ Covered |
| AC | Redis lost during any other command: command keeps running, events logged and dropped | event-system: Redis lost while a command runs | ✅ Covered |
| AC | No running cluster at start: non-zero before the first cycle | live-stream-metrics: No running cluster at start | ✅ Covered |
| AC | Cluster torn down mid-stream: keeps running, each metric's failure reported once | live-stream-metrics: Cluster torn down mid-stream | ✅ Covered |
| AC | Recorded tunnel stops carrying traffic: no tunnel started or replaced; one failure per metric; recovery when it reconnects | live-stream-metrics: Tunnel stops carrying traffic; live-stream-metrics: Stream query uses the recorded tunnel | ✅ Covered |
| AC | New tunnel on a different port: used from the next cycle; no live tunnel: one failure per metric saying run `socks start` | live-stream-metrics: A new tunnel is recorded on another port; live-stream-metrics: No live tunnel recorded; live-stream-metrics: Recorded PID now belongs to another program | ✅ Covered |
| AC | Cycle still running at the next tick (including a Redis reconnect wait): tick skipped, no overlap | live-stream-metrics: Slow cycle skips a tick; live-stream-metrics: Cycle waiting on a Redis reconnect | ✅ Covered |
| AC | `metrics list` prints every accepted name, one per line | live-stream-metrics: Names listed | ✅ Covered |
| AC | Ctrl-C or SIGTERM, even mid-cycle: exit 0, nothing more published; second signal: exit at once with 130 or 143 | live-stream-metrics: Stopped between cycles; live-stream-metrics: Stopped in the middle of a cycle; live-stream-metrics: Second signal while stopping | ✅ Covered |
| AC | `server` serves REST status endpoints and no MCP endpoint, writes no `.mcp.json`, prints no `/sse` URL; no command carries `@McpCommand` | server: Server serves REST status endpoints; server: No MCP endpoint; server: No .mcp.json and no /sse URL; server: No command carries the MCP annotation; server: Help text describes the REST server | ✅ Covered |
| Risk | Redis blast radius: every command with `EASY_DB_LAB_REDIS_URL` set changes at startup and mid-run | event-system: Fail fast when Redis unavailable; event-system: Redis lost while a command runs | ✅ Covered |
| Risk | MCP removal: external `/sse` and `.mcp.json` users lose them; REST endpoints unchanged | server: No MCP endpoint; server: No .mcp.json and no /sse URL; server: GET /status returns full JSON | ✅ Covered |
| Risk | Tunnel never repaired by the stream | live-stream-metrics: Tunnel stops carrying traffic; live-stream-metrics: No live tunnel recorded | ✅ Covered |
| Risk | Gauge overlap for up to 30 s after `cassandra use` | live-stream-metrics: Build-switch overlap is documented | ✅ Covered |
| Risk | Rate-based metrics include a stopped node's counter for up to the 1 m window | None | ⚠️ Excluded — inherent to `rate()` and accepted in the design; no behaviour to specify |
| Risk | Interrupts: an interrupted query must not be reported after a stop | live-stream-metrics: Stopped in the middle of a cycle | ✅ Covered |
| Risk | Issue 935 also plans a `metrics` group | None | ⚠️ Excluded — coordination between issues, not a behaviour; whichever lands second adds to the group |
| Risk | `timestamp()` value semantics on the pinned Mimir | live-stream-metrics: Stale sample next to a fresh one; live-stream-metrics: Every catalogue query parses on the pinned Mimir | ✅ Covered |
| Owner addition | `cassandra stress start` publishes `Stress.JobStarted` with the host | `stress-testing: Start reports the host` | ✅ Covered |
| Owner addition | `metrics stream` publishes `Stress.JobCompleted` when a stress job finishes | `live-stream-metrics: Job succeeds`, `Job fails`, `Job already finished at start`, `Job deleted while running`, `Job list fails` | ✅ Covered |
