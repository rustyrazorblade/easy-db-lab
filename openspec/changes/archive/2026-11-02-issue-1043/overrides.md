# Overrides for issue-1043

## Overrides existing behavior

### live-stream-metrics: Mimir PromQL query capability

**Currently:** "The system SHALL support querying Mimir via its Prometheus HTTP API (`/prometheus/api/v1/query`) through the existing SOCKS proxy infrastructure, sending the cluster's tenant in the `X-Scope-OrgID` header."  Scenarios: "Instant query returns metric values", "Query fails gracefully", "Query uses SOCKS proxy" (the request is routed through the existing SOCKS proxy), "Query carries the tenant".

**This change:** The requirement keeps the Prometheus HTTP API and the tenant header.  The metrics stream reaches Mimir through the recorded SOCKS tunnel without starting or replacing one, or directly when Tailscale is active.  "Query uses SOCKS proxy" is replaced by "Stream query uses the recorded tunnel" and "Stream query goes direct over Tailscale".  The other three scenarios are kept.

### live-stream-metrics: System metrics streaming

**Currently:** "The system SHALL publish per-node system metrics to the Redis pub/sub channel every 5 seconds when the server is running with Redis configured.  The queries SHALL be scoped to the current cluster."  A `Metrics.System` event per cycle, keyed by `db-0`, `db-1`.

**This change:** Removed — no replacement in the server.  `metrics stream` publishes `node.*` values as `Metrics.Sample` events, one per host and metric, keyed by `db0`.

### live-stream-metrics: Cassandra metrics streaming

**Currently:** "The system SHALL publish cluster-wide Cassandra metrics to the Redis pub/sub channel every 5 seconds when the cluster is running Cassandra."  A `Metrics.Cassandra` event per cycle.

**This change:** Removed — no replacement in the server.  `metrics stream` publishes `cassandra.*` values as `Metrics.Sample` events.

### live-stream-metrics: Metrics use dashboard-consistent queries

**Currently:** "The system SHALL use the same PromQL expressions as the Grafana dashboards to ensure metric values are consistent."  Scenarios give the banned CPU form and `histogram_quantile` over `_bucket` series.

**This change:** Removed — no replacement.  The new "Metric catalogue and defaults" requirement states that each name uses the matching dashboard panel's query and the JMX rules' metric names.

### event-system: Multiple Output Destinations

**Currently:** "The system MUST support multiple simultaneous output destinations via fan-out. Adding a new destination requires no changes to event-emitting code."  Scenario: console, MCP and Redis all configured; all three receive the event.

**This change:** The destinations are the console and, when configured, Redis.  The scenario becomes console and Redis configured; both receive the event.

### event-system: Redis Pub/Sub Destination

**Currently:** "The system MUST support publishing events to a Redis pub/sub channel using the Jedis client library, configured via environment variable."  Scenario "Fail fast when Redis unavailable": the tool fails fast with a connection error.  In code, a mid-run publish failure throws through `EventBus.emit` and stops the command.

**This change:** The startup check stays fail-fast, and its message names `EASY_DB_LAB_REDIS_URL` and the URL.  After startup, publishing is best effort for every command: a failed publish is logged and the event dropped.  New scenario "Redis lost while a command runs".  The other scenarios are kept.

### server: Server Lifecycle

**Currently:** "The system MUST provide a server that AI assistants and HTTP clients can connect to for cluster management."  Scenarios: "Server accepts MCP and REST connections" and "MCP tools available on connect".

**This change:** A REST status server only.  No MCP endpoint, no MCP tools, no `.mcp.json`, no command with `@McpCommand`.  The ready event names only the status and swagger URLs.  Scenarios: "Server serves REST status endpoints", "No MCP endpoint", "No .mcp.json and no /sse URL", "No command carries the MCP annotation", "Help text describes the REST server".

### server: REST Status Endpoints

**Currently:** "The server MUST expose REST HTTP endpoints for programmatic access to cluster status, independent of the MCP protocol."

**This change:** "The server MUST expose REST HTTP endpoints for programmatic access to cluster status."  The MCP clause is dropped.  The four scenarios are unchanged.

### server: Structured Event Streaming

**Currently:** "The system MUST stream structured events to connected MCP clients."

**This change:** Removed — no replacement.  Subscribers use the Redis channel.

### server: Optional Metrics Collection

**Currently:** "The server MUST optionally collect and publish live metrics when a Redis connection is configured."  The `MetricsCollector` polls Mimir and publishes to Redis.

**This change:** Removed — no replacement in the server.  `metrics stream` replaces it.

### observability-store: Every observability backend runs native multi-tenancy

**Currently:** The reader list is "the Grafana datasources, `logs query`, `spark logs`, the EMR step log lookup, the MCP metrics collector, `status`, and the teardown flush".

**This change:** "the MCP metrics collector" becomes "`metrics stream`".  The rest of the requirement and all six scenarios are unchanged.

### observability-store: Every series and every log line carries its cluster

**Currently:** "Readers the tool runs (`logs query`, `spark logs`, the EMR step log lookup and the MCP metrics collector) SHALL scope their queries to the current cluster by default."

**This change:** "the MCP metrics collector" becomes "`metrics stream`".  The rest of the requirement and all six scenarios are unchanged.

## Conflicts with other in-flight changes

Checked: issue-1000, issue-1017, issue-1018, issue-1022, issue-966, issue-970, issue-971, issue-992, ssm-packer-builds, ssm-ssh-transport.

- No other in-flight change touches `live-stream-metrics` or `event-system`.
- `observability-store`: issue-966 modifies "Observability data lands in one layout in the account bucket", "The cluster cannot delete observability objects" and "Data reaches S3 while the cluster is up and survives a restart".  issue-970 adds two requirements, modifies "No observability backend deletes data automatically" and "The cluster cannot delete observability objects", and removes "Mimir reads only the cluster's own data".  issue-971 modifies "The cluster cannot delete observability objects" and "Observability images are pinned to released versions".  None of them modifies "Every observability backend runs native multi-tenancy" or "Every series and every log line carries its cluster", the two this change modifies.  No conflict.
- `server`: issue-971 adds "Status lists the endpoints of each running kit", whose text says "the server's status (REST `GET /status` and the MCP status)".  This change removes the MCP status.  The requirements differ, so there is no incompatible modification, but the wording goes stale.  Whichever change is archived second should drop "and the MCP status" from that requirement, and issue-971's code must not add kit endpoints to an MCP status handler that this change deletes.
- issue-1000, issue-1017, issue-1018, issue-1022, issue-992, ssm-packer-builds and ssm-ssh-transport touch unrelated capabilities.
