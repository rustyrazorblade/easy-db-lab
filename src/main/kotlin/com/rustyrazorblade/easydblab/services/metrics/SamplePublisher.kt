package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventContext
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.RedisPublisher
import redis.clients.jedis.exceptions.JedisException
import java.time.Instant

/**
 * Where `metrics stream` sends its samples: straight to Redis, never through the event bus, so a
 * sample never reaches the console and the stream sees a Redis failure as it happens.
 */
interface SamplePublisher {
    /** The Redis URL, for the message that names it when Redis is lost. */
    val url: String

    /**
     * Publishes [sample] as an event envelope.
     *
     * @throws RedisPublishException when Redis does not take it.
     */
    fun publish(sample: Event.Metrics.Sample)

    /**
     * Asks Redis to answer.
     *
     * @throws RedisPublishException when it does not.
     */
    fun ping()
}

/** Redis did not answer a ping or did not take a sample. */
class RedisPublishException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

/**
 * [SamplePublisher] on the process's shared [RedisPublisher].  It builds each envelope the way the
 * event bus does: the current time and the command name from [EventContext].
 */
class RedisSamplePublisher(
    private val publisher: RedisPublisher,
) : SamplePublisher {
    override val url: String = publisher.endpoint.url

    override fun publish(sample: Event.Metrics.Sample) {
        val envelope = EventEnvelope(event = sample, timestamp = Instant.now().toString(), commandName = EventContext.current())
        redisCall("publish") { publisher.publish(EventEnvelope.toJson(envelope)) }
    }

    override fun ping() {
        redisCall("ping") { publisher.ping() }
    }

    private fun redisCall(
        what: String,
        call: () -> Unit,
    ) {
        try {
            call()
        } catch (e: JedisException) {
            throw RedisPublishException("Redis $what to $url failed: ${e.message}", e)
        }
    }
}
