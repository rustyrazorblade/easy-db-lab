## Why

Lettuce is broken when used inside a Kubernetes cluster environment and is overly complex for our needs. We only use Redis for simple pub/sub publishing in the event bus. Jedis is a simpler, more straightforward Redis client that works reliably in K8s.

## What Changes

- Replace the `io.lettuce:lettuce-core` dependency with `redis.clients:jedis`
- Rewrite `RedisEventListener` to use Jedis pub/sub API instead of Lettuce
- Update `RedisEventListenerTest` to use Jedis subscriber API
- Remove Lettuce from `libs.versions.toml` and add Jedis

## Capabilities

### New Capabilities

None — this is a like-for-like library swap.

### Modified Capabilities

- `event-system`: Redis client implementation changes from Lettuce to Jedis. Same pub/sub behavior, different library.

## Impact

- **Dependencies**: Remove `io.lettuce:lettuce-core:6.5.5.RELEASE`, add `redis.clients:jedis` (latest stable)
- **Code**: `RedisEventListener.kt` (rewrite internals), `RedisEventListenerTest.kt` (update subscriber setup)
- **Configuration**: `EventBusModule.kt` — no changes expected (same `RedisEventListener` interface)
- **Wire format**: No change — same JSON published to same Redis channels
- **User-facing behavior**: No change — same env var (`EASY_DB_LAB_REDIS_URL`), same channel semantics
