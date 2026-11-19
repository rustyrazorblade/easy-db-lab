package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.services.CommandFailureReporter

/**
 * Builds the command line parser at startup, turning a failure into one report instead of a raw
 * stack trace.
 *
 * Building the parser registers kit subcommands and resolves beans, outside any command's
 * lifecycle, so nothing else would report a failure there. The reporter is resolved before the
 * parser is built, so a broken parser bean cannot also break the report. An [Error] is not ours to
 * report and propagates.
 */
object ParserStartup {
    /** The parser [build] returned, or null after [reporter] has reported why it could not be built. */
    fun <T> buildOrReport(
        reporter: CommandFailureReporter,
        build: () -> T,
    ): T? =
        runCatchingException(build)
            .onFailure { reporter.report(it) }
            .getOrNull()
}
