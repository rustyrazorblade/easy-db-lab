# Server Package (`server/`)

This package implements `easy-db-lab server`: a REST status server for programmatic access to the cluster status. It has no MCP endpoint and registers no tools.

## Architecture

```
HTTP Client → Ktor/Netty (StatusServer) → /status, /stress/status, /swagger
                                              ↓
                                        CachedStatus (StatusCache: background refresh every 30s)
```

`commands/Server` builds the `StatusCache`, starts it, starts the `StatusServer`, and blocks until it is stopped. Once it is bound, `StatusServer` emits `Event.Server.Ready(statusUrl, swaggerUrl)`.

## Files

| File | Purpose |
|------|---------|
| `StatusServer.kt` | The Ktor/Netty server: the `/status` and `/stress/status` routes with their OpenAPI `describe {}` blocks, `swaggerUI("/swagger")`, and `CachedStatus`, the interface the routes read. `start(port, bind)` returns a `RunningStatusServer` (bound port, `close()`) |
| `ClusterStatusCollector.kt` | The one place that fetches the status facts (cluster state, EC2, K3s, EMR, OpenSearch, security groups, S3 paths, kits, stress jobs, Cassandra version over SSH) and builds the typed `StatusResponse`. `StatusCache` and the `status` command both read what it builds, so the CLI and `/status` cannot disagree. The fetches (EC2, security group, stress jobs, EMR, OpenSearch, pods) run at the same time on virtual threads, one executor per `collect()` call, closed before it returns; the live version starts when EC2 returns, asks every running db host at once and collects every good answer, in host order, within `Constants.Cassandra.LIVE_VERSION_PROBE_TIMEOUT_SECONDS` in total (`CassandraVersionInfo.hosts`; `mixed` when they differ; `unanswered` names the hosts that gave none; `version` is the first answering host in cluster order). The probe threads are interrupted and not waited for, so a hung SSH connect cannot hold the report past the bound. Returns a `StatusReport`: the response plus the reasons behind a security group or stress job gap, which only the CLI shows |
| `StatusCache.kt` | Schedules `ClusterStatusCollector` every refresh interval and caches the result; the `--auto-shutdown` VPC watchdog, which emits `Server.InfrastructureGone` |
| (Koin) | `ClusterStatusCollector` is a `factory` in `ServicesModule`; `Status` and `StatusCache` get it by `inject()`, so a test registers it in its own module |
| `StatusResponse.kt` | Status API data classes (serializable) |

## Endpoints

- `GET /status` — the whole cached status; `?section=<name>` returns one section (400 for an unknown one); `?live=true` refreshes first. 503 until the first refresh completes.
- `GET /stress/status` — the `stressJobs` section.
- `GET /swagger` — the API docs, generated from the routes.

## StatusCache

- It only schedules and caches. The collector decides what each section holds: ClickHouse and Spark S3 paths are in the per-cluster data bucket (where `spark submit` uploads and ClickHouse stores), Cassandra and EMR logs are under the cluster prefix of the account bucket; the OpenSearch endpoint carries `https://`; a security group that cannot be described is present with null rules.

- Default refresh: 30 seconds (`--refresh`).
- Sections: `cluster`, `nodes`, `networking`, `securityGroup`, `spark`, `opensearch`, `s3`, `kubernetes`, `stressJobs`, `cassandraVersion`, `accessInfo`.
- The refresh calls K3s and Kubernetes through Fabric8, so `server` carries `@RequiresProxy`.

## Live metrics

The server does not stream metrics. `metrics stream` (`commands/metrics/`, `services/metrics/`) publishes chosen metrics to Redis.

## Tests

`StatusServerIntegrationTest` runs the real server on a free port with a fake `CachedStatus`. `ServerProcessIntegrationTest` runs `easy-db-lab server` as a process and checks the ready output, that `/sse` is not served and that no `.mcp.json` is written. `StatusCacheTest` covers the cache.
