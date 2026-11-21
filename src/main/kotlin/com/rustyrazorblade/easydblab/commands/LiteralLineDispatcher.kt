package com.rustyrazorblade.easydblab.commands

import org.jline.shell.Pipeline
import org.jline.shell.impl.DefaultCommandDispatcher
import org.jline.terminal.Terminal

/**
 * The REPL's jline dispatcher: it runs each line as one command, exactly as typed, as the jline 3
 * REPL did.
 *
 * jline 4's default dispatcher rewrites a line before any command sees it: it expands aliases and
 * the local `$VAR` and `~`, stores a leading `NAME=value` as a shell variable, runs a trailing `&`
 * in the background and splits on `|`, `&&`, `;` and `>`. A REPL line is one easy-db-lab command
 * whose text often holds a remote shell command (`exec run "ps aux | grep java"`), so this
 * dispatcher skips all of that and hands the whole line to the command it names.
 */
class LiteralLineDispatcher(
    terminal: Terminal,
) : DefaultCommandDispatcher(terminal) {
    override fun execute(line: String?): Any? {
        if (line.isNullOrBlank()) return null
        return execute(Pipeline.of(line.trim()).build())
    }
}
