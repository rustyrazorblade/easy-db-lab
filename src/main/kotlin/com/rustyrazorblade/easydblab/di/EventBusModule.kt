package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.ConsoleEventListener
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.RedisEventListener
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.dsl.module

private val log = KotlinLogging.logger {}

/**
 * Koin module for the event bus infrastructure.
 *
 * Registers [EventBus] as a singleton and wires up the [ConsoleEventListener]
 * for backward-compatible console output. Conditionally registers a
 * [RedisEventListener] when the EASY_DB_LAB_REDIS_URL environment variable is set.
 */
val eventBusModule =
    module {
        single {
            EventBus().apply {
                addListener(ConsoleEventListener())

                val redisUrl = System.getenv(Constants.EventBus.REDIS_URL_ENV_VAR)
                if (!redisUrl.isNullOrBlank()) {
                    try {
                        val redisListener = RedisEventListener(redisUrl)
                        addListener(redisListener)
                        log.info { "Redis EventListener registered for URL: $redisUrl" }
                    } catch (e: Exception) {
                        log.error(e) { "Failed to create Redis EventListener from URL: $redisUrl" }
                    }
                }
            }
        }
    }
