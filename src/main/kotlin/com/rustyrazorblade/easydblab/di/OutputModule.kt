package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.output.CompositeOutputHandler
import com.rustyrazorblade.easydblab.output.ConsoleNodeOutput
import com.rustyrazorblade.easydblab.output.ConsoleOutputHandler
import com.rustyrazorblade.easydblab.output.LoggerOutputHandler
import com.rustyrazorblade.easydblab.output.NodeOutput
import com.rustyrazorblade.easydblab.output.NodeOutputTail
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin module for output handling dependency injection.
 *
 * Provides OutputHandler implementations for different use cases:
 * - Composite output (default) - combines console and logger output
 * - Console output (named instance)
 * - Logger output (named instance)
 * - Named instances for specific use cases
 */
val outputModule =
    module {
        // Default output handler - composite with console and logger
        single<OutputHandler> {
            CompositeOutputHandler(
                LoggerOutputHandler("EasyDBLab"),
                ConsoleOutputHandler(),
            )
        }

        // Named output handlers for specific use cases
        single<OutputHandler>(named("console")) { ConsoleOutputHandler() }
        single<OutputHandler>(named("logger")) { LoggerOutputHandler("Application") }

        // Raw output of commands run on cluster nodes: one instance, so one lock and one set of tails
        single { ConsoleNodeOutput() }
        single<NodeOutput> { get<ConsoleNodeOutput>() }
        single<NodeOutputTail> { get<ConsoleNodeOutput>() }
    }
