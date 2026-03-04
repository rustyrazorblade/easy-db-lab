package com.rustyrazorblade.easydblab.events

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import java.net.URI

/**
 * EventListener that publishes EventEnvelopes to a Redis pub/sub channel.
 *
 * Serializes events to JSON via kotlinx.serialization and publishes non-blocking.
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
    private val client: RedisClient
    private val connection: StatefulRedisConnection<String, String>

    init {
        val uri = URI(redisUrl)
        val pathChannel = uri.path?.removePrefix("/")?.takeIf { it.isNotBlank() }
        channel = pathChannel ?: Constants.EventBus.DEFAULT_CHANNEL

        val port = if (uri.port > 0) uri.port else DEFAULT_REDIS_PORT
        val redisUri = "redis://${uri.host}:$port"
        client = RedisClient.create(redisUri)

        connection = client.connect()
        log.info { "Redis EventListener connected to $redisUri, publishing to channel: $channel" }
    }

    override fun onEvent(envelope: EventEnvelope) {
        val json = EventEnvelope.toJson(envelope)
        connection.async().publish(channel, json)
    }

    override fun close() {
        connection.close()
        client.shutdown()
        log.info { "Redis EventListener closed" }
    }
}
