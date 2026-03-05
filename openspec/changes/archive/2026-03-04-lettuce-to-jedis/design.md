## Context

The `RedisEventListener` uses Lettuce (`io.lettuce:lettuce-core:6.5.5.RELEASE`) for pub/sub publishing. Lettuce has connectivity issues inside K8s clusters. The usage is minimal — one class that calls `connect()`, `async().publish()`, and `close()`.

## Goals / Non-Goals

**Goals:**
- Replace Lettuce with Jedis for Redis pub/sub in `RedisEventListener`
- Maintain identical behavior: same URL format, same channel semantics, same fail-fast, same wire format
- Keep publishing non-blocking

**Non-Goals:**
- Changing the event bus architecture
- Adding new Redis features (subscribe, caching, etc.)
- Changing the wire format or env var configuration

## Decisions

### Use Jedis `JedisPooled` with background publish thread

Jedis is synchronous by default. To maintain non-blocking publish behavior, we'll use a `JedisPooled` instance (thread-safe, connection-pooled) and publish on a background thread or use fire-and-forget via an executor.

**Alternative considered**: Raw `Jedis` instance — not thread-safe, would require synchronization. `JedisPooled` handles this cleanly.

### Jedis version: latest stable (5.x)

Use `redis.clients:jedis:5.2.0` or latest stable 5.x. The 5.x line is mature and widely used. We avoid 7.x which is very new.

**Update**: Will check Maven Central for the exact latest 5.x stable.

### URL parsing stays in `RedisEventListener`

Keep the existing `redis://host:port/channel` parsing logic. Jedis accepts host+port directly, so we parse the URI and pass components.

## Risks / Trade-offs

- **[Jedis is synchronous]** → Use `JedisPooled` which manages a connection pool. Publish calls are fast (network I/O only, no computation) so blocking briefly is acceptable for pub/sub.
- **[Connection pool overhead]** → Minimal. Default pool is small and we only publish. One connection is enough.
