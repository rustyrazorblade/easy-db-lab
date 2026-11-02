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
| `StatusCache.kt` | Background cluster status refresh (EC2, K3s, EMR, OpenSearch, security groups, Cassandra version over SSH); the `--auto-shutdown` VPC watchdog, which emits `Server.InfrastructureGone` |
| `StatusResponse.kt` | Status API data classes (serializable) |

## Endpoints

- `GET /status` — the whole cached status; `?section=<name>` returns one section (400 for an unknown one); `?live=true` refreshes first. 503 until the first refresh completes.
- `GET /stress/status` — the `stressJobs` section.
- `GET /swagger` — the API docs, generated from the routes.

## StatusCache

- Default refresh: 30 seconds (`--refresh`).
- Sections: `cluster`, `nodes`, `networking`, `securityGroup`, `spark`, `opensearch`, `s3`, `kubernetes`, `stressJobs`, `cassandraVersion`, `accessInfo`.
- The refresh calls K3s and Kubernetes through Fabric8, so `server` carries `@RequiresProxy`.

## Live metrics

The server does not stream metrics. `metrics stream` (`commands/metrics/`, `services/metrics/`) publishes chosen metrics to Redis.

## Tests

`StatusServerIntegrationTest` runs the real server on a free port with a fake `CachedStatus`. `ServerProcessIntegrationTest` runs `easy-db-lab server` as a process and checks the ready output, that `/sse` is not served and that no `.mcp.json` is written. `StatusCacheTest` covers the cache.
