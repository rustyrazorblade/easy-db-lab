package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.events.ConsoleEventListener
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.RedisConfiguration
import com.rustyrazorblade.easydblab.events.RedisEventListener
import com.rustyrazorblade.easydblab.events.RedisPublisher
import org.koin.dsl.module

/**
 * Koin module for the event bus infrastructure.
 *
 * Registers [EventBus] as a singleton with the [ConsoleEventListener].  It reads
 * [RedisConfiguration] from the environment once; when it is [RedisConfiguration.Configured], it
 * builds the one [RedisPublisher] of the process and adds a [RedisEventListener] on it.  The
 * publisher is resolved only in that case, so a process with no Redis URL never touches Redis.
 */
val eventBusModule =
    module {
        single<RedisConfiguration> { RedisConfiguration.fromEnvironment() }

        single {
            when (val configuration = get<RedisConfiguration>()) {
                is RedisConfiguration.Configured -> RedisPublisher(configuration.endpoint)
                RedisConfiguration.NotConfigured -> error("RedisPublisher requested but EASY_DB_LAB_REDIS_URL is not set")
            }
        }

        single {
            EventBus().apply {
                addListener(ConsoleEventListener())
                when (get<RedisConfiguration>()) {
                    is RedisConfiguration.Configured -> addListener(RedisEventListener(get()))
                    RedisConfiguration.NotConfigured -> Unit
                }
            }
        }
    }
