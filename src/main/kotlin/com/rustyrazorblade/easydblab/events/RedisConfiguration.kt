package com.rustyrazorblade.easydblab.events

import com.rustyrazorblade.easydblab.Constants

/**
 * Whether this process publishes to Redis, read once from `EASY_DB_LAB_REDIS_URL`.
 *
 * The event bus adds its Redis listener only when the configuration is [Configured], and the
 * metrics stream refuses to start when it is [NotConfigured].
 */
sealed interface RedisConfiguration {
    /** `EASY_DB_LAB_REDIS_URL` is unset or blank. */
    data object NotConfigured : RedisConfiguration

    /** `EASY_DB_LAB_REDIS_URL` names [endpoint]. */
    data class Configured(
        val endpoint: RedisEndpoint,
    ) : RedisConfiguration

    companion object {
        /** Reads `EASY_DB_LAB_REDIS_URL` through [getenv]. */
        fun fromEnvironment(getenv: (String) -> String? = System::getenv): RedisConfiguration =
            getenv(Constants.EventBus.REDIS_URL_ENV_VAR)
                ?.takeIf { it.isNotBlank() }
                ?.let { Configured(RedisEndpoint.parse(it)) }
                ?: NotConfigured
    }
}
