## 1. Dependency Swap

- [x] 1.1 Replace `lettuce-core` with `jedis` in `gradle/libs.versions.toml` and `build.gradle.kts`
- [x] 1.2 Verify the project compiles with the new dependency (expect compile errors in RedisEventListener)

## 2. Rewrite RedisEventListener

- [x] 2.1 Rewrite `RedisEventListener` to use `JedisPooled` instead of Lettuce `RedisClient`/`StatefulRedisConnection`
- [x] 2.2 Maintain same URL parsing, channel logic, fail-fast behavior, and `close()` lifecycle

## 3. Update Tests

- [x] 3.1 Rewrite `RedisEventListenerTest` to use Jedis subscriber API instead of Lettuce `RedisPubSubAdapter`
- [x] 3.2 Verify all three test scenarios pass: pub/sub round-trip, fail-fast on unavailable Redis, default channel

## 4. Validate

- [x] 4.1 Run `./gradlew :test` to confirm all tests pass
- [x] 4.2 Run `./gradlew ktlintCheck` and `./gradlew detekt` to confirm code quality
