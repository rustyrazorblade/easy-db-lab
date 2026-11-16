package com.rustyrazorblade.easydblab.events

import io.github.oshai.kotlinlogging.KotlinLogging
import redis.clients.jedis.exceptions.JedisException

/**
 * EventListener that publishes every event envelope, as JSON, to the Redis channel of [publisher].
 *
 * The startup check is the publisher's: it pings when it is built, so a Redis that does not answer
 * stops the process before any command runs.  After that, publishing is best effort.  A failed
 * publish is logged and the event dropped, so a short Redis outage never stops a command.
 */
class RedisEventListener(
    private val publisher: RedisPublisher,
) : EventListener {
    override fun onEvent(envelope: EventEnvelope) {
        try {
            publisher.publish(EventEnvelope.toJson(envelope))
        } catch (e: JedisException) {
            log.warn(e) { "Dropped an event: Redis at ${publisher.endpoint.url} did not take it" }
        }
    }

    override fun close() {
        publisher.close()
        log.info { "Redis EventListener closed" }
    }

    private companion object {
        private val log = KotlinLogging.logger {}
    }
}
