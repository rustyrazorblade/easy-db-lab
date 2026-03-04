package com.rustyrazorblade.easydblab.events

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import redis.clients.jedis.JedisPooled
import java.net.URI

/**
 * EventListener that publishes EventEnvelopes to a Redis pub/sub channel.
 *
 * Serializes events to JSON via kotlinx.serialization and publishes using Jedis.
 * Fails fast on connection errors — if Redis is configured, it must be reachable.
 *
 * URL format: redis://host:port/channel-name
 * If no path is specified, uses the default channel from Constants.
 *
 * @param redisUrl The Redis URL (redis://host:port/channel)
 */
class RedisEventListener(
    redisUrl: String,
) : EventListener {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val DEFAULT_REDIS_PORT = 6379
    }

    private val channel: String
    private val jedis: JedisPooled

    init {
        val uri = URI(redisUrl)
        val pathChannel = uri.path?.removePrefix("/")?.takeIf { it.isNotBlank() }
        channel = pathChannel ?: Constants.EventBus.DEFAULT_CHANNEL

        val host = uri.host
        val port = if (uri.port > 0) uri.port else DEFAULT_REDIS_PORT

        jedis = JedisPooled(host, port)

        // Fail fast: verify connectivity by pinging Redis
        jedis.ping()
        log.info { "Redis EventListener connected to redis://$host:$port, publishing to channel: $channel" }
    }

    override fun onEvent(envelope: EventEnvelope) {
        val json = EventEnvelope.toJson(envelope)
        jedis.publish(channel, json)
    }

    override fun close() {
        jedis.close()
        log.info { "Redis EventListener closed" }
    }
}
