package com.rustyrazorblade.easydblab

import com.github.ajalt.mordant.TermColors
import com.rustyrazorblade.easydblab.di.KoinModules
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import org.koin.core.context.startKoin
import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    // Suppress kotlin-logging initialization message to prevent pollution of machine-readable output
    KotlinLoggingConfiguration.logStartupMessage = false

    // Initialize Koin dependency injection
    val koin =
        startKoin {
            modules(KoinModules.getAllModules())
        }.koin

    // Build the event bus first: with EASY_DB_LAB_REDIS_URL set it pings Redis, and a Redis that
    // does not answer must stop the tool before any command runs, naming the variable and URL.
    when (val startup = StartupRedisCheck.resolveEventBus(koin)) {
        is StartupRedisCheck.Result.Ready -> Unit
        is StartupRedisCheck.Result.RedisUnavailable -> {
            with(TermColors()) { System.err.println(red(startup.message)) }
            exitProcess(Constants.ExitCodes.ERROR)
        }
    }

    CommandLineParser().eval(arguments)
}
