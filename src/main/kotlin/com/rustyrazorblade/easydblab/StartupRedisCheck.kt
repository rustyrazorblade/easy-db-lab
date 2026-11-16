package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.RedisStartupException
import org.koin.core.Koin
import org.koin.core.error.InstanceCreationException

/**
 * The startup check `Main` runs right after Koin starts: it builds the [EventBus], which pings
 * Redis when `EASY_DB_LAB_REDIS_URL` is set.
 *
 * Koin wraps a failure in its own creation exception, whose message names neither the variable
 * nor the URL.  This check finds the [RedisStartupException] in the cause chain, so `Main`
 * can print its message and exit before any command runs.  It is the one failure with no typed
 * event, because the bus itself could not be built.
 */
object StartupRedisCheck {
    /** What building the bus gave. */
    sealed interface Result {
        /** The bus is built; Redis answered, or no Redis is configured. */
        data class Ready(
            val eventBus: EventBus,
        ) : Result

        /** Redis did not answer or its URL cannot be used; [message] names the variable and the URL. */
        data class RedisUnavailable(
            val message: String,
        ) : Result
    }

    /**
     * Builds the [EventBus] from [koin].
     *
     * @throws InstanceCreationException when building the bus fails for a reason other than Redis.
     */
    fun resolveEventBus(koin: Koin): Result =
        try {
            Result.Ready(koin.get<EventBus>())
        } catch (e: InstanceCreationException) {
            val redis = redisFailure(e) ?: throw e
            Result.RedisUnavailable(redis.message.orEmpty())
        }

    /** The [RedisStartupException] (Redis not answering, or an unusable URL) in [failure]'s cause chain, if there is one. */
    fun redisFailure(failure: Throwable): RedisStartupException? =
        generateSequence(failure) { it.cause }
            .filterIsInstance<RedisStartupException>()
            .firstOrNull()
}
