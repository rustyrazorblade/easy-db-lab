package com.rustyrazorblade.easydblab.events

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import redis.clients.jedis.RedisClient
import redis.clients.jedis.exceptions.JedisException

/**
 * The one Redis connection of a process, shared by the event bus listener and the metrics stream.
 *
 * The constructor pings Redis, so a process with `EASY_DB_LAB_REDIS_URL` set fails at startup when
 * Redis does not answer, with a message that names the variable and the URL.  After that,
 * [publish] and [ping] throw Jedis's own runtime exceptions; each caller decides what a failure
 * means for it.  The Jedis client pools its connections and opens a new one on the next call, so a
 * Redis that comes back is used again with no new publisher.
 *
 * @throws RedisUnavailableException from the constructor when Redis does not answer the ping.
 */
class RedisPublisher(
    val endpoint: RedisEndpoint,
) : AutoCloseable {
    private val client: RedisClient = RedisClient.create(endpoint.host, endpoint.port)

    init {
        try {
            client.ping()
        } catch (e: JedisException) {
            client.close()
            throw RedisUnavailableException(endpoint, e)
        }
        log.info { "Connected to Redis at ${endpoint.url}, channel ${endpoint.channel}" }
    }

    /** Publishes [json] on the endpoint's channel. */
    fun publish(json: String) {
        client.publish(endpoint.channel, json)
    }

    /** Asks Redis to answer; throws when it does not. */
    fun ping() {
        client.ping()
    }

    override fun close() {
        client.close()
    }

    private companion object {
        private val log = KotlinLogging.logger {}
    }
}

/**
 * Redis did not answer when the process started.  The message names `EASY_DB_LAB_REDIS_URL` and
 * the URL, because the user set that variable and has to fix one or the other.
 */
class RedisUnavailableException(
    endpoint: RedisEndpoint,
    cause: Throwable,
) : RuntimeException(
        "Redis at ${endpoint.url} (from ${Constants.EventBus.REDIS_URL_ENV_VAR}) did not answer: ${cause.message}",
        cause,
    )
