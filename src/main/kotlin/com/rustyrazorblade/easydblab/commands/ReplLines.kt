package com.rustyrazorblade.easydblab.commands

import org.jline.shell.LineExpander
import org.jline.shell.Pipeline
import org.jline.shell.ShellBuilder
import org.jline.shell.impl.PipelineParser

/**
 * Keeps each REPL line literal, as the jline 3 REPL did: jline 4's shell would otherwise expand
 * `$VAR` and `~` from the local machine and split the line on `|`, `&&`, `;`, `>` and a trailing
 * `&`. A REPL line is one easy-db-lab command whose text often holds a remote shell command
 * (`exec run "ps aux | grep java"`), so every character reaches picocli unchanged.
 */
object ReplLines {
    /** Returns the line unchanged: no variable, `~` or other expansion. */
    val passThrough = LineExpander { line, _ -> line }

    /** Parses every line as one stage, so no character is read as a shell operator. */
    val wholeLine: PipelineParser =
        object : PipelineParser() {
            override fun parse(line: String): Pipeline = Pipeline.of(line).build()
        }

    /** Applies [passThrough] and [wholeLine] to this shell. */
    fun literal(builder: ShellBuilder): ShellBuilder = builder.lineExpander(passThrough).pipelineParser(wholeLine)
}
