package com.rustyrazorblade.easydblab.events

import com.rustyrazorblade.easydblab.Constants
import java.net.URI

/**
 * Where events go on Redis: the host, the port and the pub/sub channel that one
 * `EASY_DB_LAB_REDIS_URL` value names.
 *
 * The URL has the form `redis://host:port/channel`.  A URL with no port uses
 * [Constants.EventBus.DEFAULT_REDIS_PORT], and a URL with no path uses
 * [Constants.EventBus.DEFAULT_CHANNEL].  The event bus listener and the metrics stream both
 * publish through one endpoint, so the parsing lives here once.
 *
 * @property url The URL as the user gave it, for messages that name it.
 */
data class RedisEndpoint(
    val url: String,
    val host: String,
    val port: Int,
    val channel: String,
) {
    companion object {
        /** Parses [url], filling in the default port and channel. */
        fun parse(url: String): RedisEndpoint {
            val uri = URI(url)
            val channel =
                uri.path
                    ?.removePrefix("/")
                    ?.takeIf { it.isNotBlank() }
                    ?: Constants.EventBus.DEFAULT_CHANNEL
            val port = if (uri.port > 0) uri.port else Constants.EventBus.DEFAULT_REDIS_PORT
            return RedisEndpoint(url = url, host = uri.host, port = port, channel = channel)
        }
    }
}
