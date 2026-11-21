package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import picocli.CommandLine

/**
 * The REPL's picocli execution strategy: it runs the deepest command a line names through
 * [runCommand] (the `CommandExecutor` lifecycle), and ends a line that Ctrl-C cancels.
 *
 * Ctrl-C makes jline interrupt the command thread. The lifecycle lets the [InterruptedException]
 * through, because cancelling is not a command failure, and picocli would print it as a stack
 * trace and leave the thread's interrupt flag set, so the next line's first blocking call would
 * fail. This strategy clears the flag, prints one `Interrupted` line to the command's error
 * stream and returns the error exit code, so the REPL is ready for the next line.
 *
 * A command that is not a [PicoCommand] (a plain picocli `Runnable` or `Callable`) runs as
 * picocli runs it, through [CommandLine.RunLast], which wraps the command's exception in a
 * [CommandLine.ExecutionException]; an interrupt there ends the line the same way.
 */
class ReplExecutionStrategy(
    private val runCommand: (PicoCommand) -> Int,
) : CommandLine.IExecutionStrategy {
    override fun execute(parseResult: CommandLine.ParseResult): Int {
        var deepest = parseResult
        while (deepest.subcommand() != null) deepest = deepest.subcommand()
        val command = deepest.commandSpec().userObject() as? PicoCommand
        return try {
            if (command != null) runCommand(command) else CommandLine.RunLast().execute(parseResult)
        } catch (_: InterruptedException) {
            interrupted(deepest)
        } catch (e: CommandLine.ExecutionException) {
            if (e.cause !is InterruptedException) throw e
            interrupted(deepest)
        }
    }

    /** Clears the interrupt flag and reports the cancelled line on [line]'s error stream. */
    private fun interrupted(line: CommandLine.ParseResult): Int {
        Thread.interrupted()
        line
            .commandSpec()
            .commandLine()
            .err
            .println(INTERRUPTED_MESSAGE)
        return Constants.ExitCodes.ERROR
    }

    private companion object {
        const val INTERRUPTED_MESSAGE = "Interrupted"
    }
}
