package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.runCatchingException
import org.koin.core.component.KoinComponent
import picocli.CommandLine

/**
 * PicoCLI factory that uses Koin for dependency injection.
 *
 * This enables PicoCLI to instantiate command classes while Koin
 * provides their dependencies (Context, services, etc.).
 *
 * Commands are registered as factories in Koin (via CommandsModule)
 * to ensure a fresh instance is created for each execution, which
 * is important for the REPL and server.
 *
 * For classes not registered in Koin (like parent Runnable commands),
 * this factory falls back to PicoCLI's default factory.
 */
class KoinCommandFactory :
    CommandLine.IFactory,
    KoinComponent {
    override fun <K : Any> create(cls: Class<K>): K =
        // Try to get from Koin first (for commands registered in Koin); fall back to the default
        // factory for classes not in Koin. This handles parent commands (Runnable) and other
        // PicoCLI internals.
        runCatchingException { getKoin().get(cls.kotlin) }
            .getOrElse { CommandLine.defaultFactory().create(cls) }
}
