package com.rustyrazorblade.easydblab.events

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ssh.redactUrlCredentials
import java.net.URI
import java.net.URISyntaxException

/**
 * Where events go on Redis: the host, the port and the pub/sub channel that one
 * `EASY_DB_LAB_REDIS_URL` value names.
 *
 * The URL has the form `redis://host:port/channel`; the connection uses no TLS and no credentials,
 * so `rediss://` and a URL that names a user are refused.  A URL with no port uses
 * [Constants.EventBus.DEFAULT_REDIS_PORT], and a URL with no path uses
 * [Constants.EventBus.DEFAULT_CHANNEL].  The event bus listener and the metrics stream both
 * publish through one endpoint, so the parsing lives here once.
 *
 * @property url The URL as the user gave it, with any user name and password replaced by `***`.
 *   Every message that names the URL uses this, so a password never reaches a log or an event.
 */
data class RedisEndpoint(
    val url: String,
    val host: String,
    val port: Int,
    val channel: String,
) {
    companion object {
        private const val SCHEME = "redis"
        private const val TLS_SCHEME = "rediss"

        /**
         * Parses [url], filling in the default port and channel.
         *
         * @throws RedisConfigurationException when [url] does not parse, is not a `redis://` URL (TLS
         *   `rediss://` is not supported), names a user or password (not supported), or names no host.
         */
        fun parse(url: String): RedisEndpoint {
            val uri =
                try {
                    URI(url)
                } catch (e: URISyntaxException) {
                    throw RedisConfigurationException(url, "it is not a valid URL (${e.reason})")
                }
            when (uri.scheme?.lowercase()) {
                SCHEME -> Unit
                TLS_SCHEME -> throw RedisConfigurationException(url, "TLS (rediss://) is not supported; use redis://")
                else -> throw RedisConfigurationException(url, "it does not start with redis://")
            }
            // The connection carries no credentials, so a URL that names a user would fail later with
            // a misleading authentication error.
            if (uri.rawUserInfo != null || uri.rawAuthority?.contains('@') == true) {
                throw RedisConfigurationException(url, "a user name or password is not supported")
            }
            val host = uri.host ?: throw RedisConfigurationException(url, "it names no host")
            val channel =
                uri.path
                    ?.removePrefix("/")
                    ?.takeIf { it.isNotBlank() }
                    ?: Constants.EventBus.DEFAULT_CHANNEL
            val port = if (uri.port > 0) uri.port else Constants.EventBus.DEFAULT_REDIS_PORT
            return RedisEndpoint(url = redactUrlCredentials(url), host = host, port = port, channel = channel)
        }
    }
}

/**
 * A Redis problem found as the process starts, before any command runs.  `Main` prints its message
 * and exits 1; the message always names `EASY_DB_LAB_REDIS_URL` and the URL, without its password.
 */
sealed class RedisStartupException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** `EASY_DB_LAB_REDIS_URL` holds a value the tool cannot use; [reason] says why. */
class RedisConfigurationException(
    url: String,
    reason: String,
) : RedisStartupException(
        "${Constants.EventBus.REDIS_URL_ENV_VAR}=${redactUrlCredentials(url)} cannot be used: $reason. " +
            "Expected redis://host:port/channel",
    )
