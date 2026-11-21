package com.rustyrazorblade.easydblab.commands

import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine
import org.jline.shell.Command
import org.jline.shell.CommandDescription
import org.jline.shell.CommandGroup
import org.jline.shell.CommandSession
import org.jline.terminal.Terminal
import org.jline.utils.AttributedString
import org.jline.utils.InfoCmp
import picocli.CommandLine

/**
 * The REPL's commands for jline's shell: one jline [Command] per top-level picocli subcommand of
 * [root], plus `cls` to clear the screen.
 *
 * jline 4 replaced the console `SystemRegistry` that picocli's own jline 3 bridge was built on, so
 * the REPL uses jline's shell API and this group is the bridge. Every command line runs through
 * [root] itself, so the execution strategy set on the root (the full command lifecycle) handles it
 * exactly as it handles a line typed at the system shell. Completion and the tail-tip description
 * walk [root] by the words typed so far, so nested subcommands and their options complete at any
 * depth.
 */
class ReplCommandGroup(
    private val root: CommandLine,
    private val terminal: Terminal,
) : CommandGroup {
    override fun name(): String = "easy-db-lab"

    // A hidden command still runs when typed; completion is what leaves it out.
    override fun commands(): Collection<Command> =
        root.subcommands.values
            .distinct()
            .map { PicocliCommand(it) } + ClearScreen(terminal)

    /** Completes the line by finding the picocli command its finished words name. */
    fun complete(
        line: ParsedLine,
        candidates: MutableList<Candidate>,
    ) {
        val target = findCommandLine(line.words(), line.wordIndex()) ?: return
        if (target.commandSpec.usageMessage().hidden()) return
        val word = line.word()
        if (word.startsWith("-")) {
            optionCandidates(target, word.substring(0, line.wordCursor()), candidates)
        } else {
            target.subcommands.values
                .distinct()
                .filterNot { it.commandSpec.usageMessage().hidden() }
                .forEach { sub ->
                    (listOf(sub.commandSpec.name()) + sub.commandSpec.aliases()).forEach {
                        candidates.add(candidate(it, it, complete = true))
                    }
                }
        }
    }

    /** The tail-tip description of the deepest command [args] name, or null for an unknown one. */
    fun describe(args: List<String>): CommandDescription? {
        val target = findCommandLine(args, args.size) ?: return null
        val spec = target.commandSpec
        val builder =
            CommandDescription
                .builder()
                .command(true)
                .mainDescription(spec.usageMessage().description().map { AttributedString(it) })
        spec
            .options()
            .filterNot { it.hidden() }
            .forEach { option ->
                val names = option.names().joinToString(" ")
                val key = if (option.arity().max() > 0) "$names=${option.paramLabel()}" else names
                builder.option(key, option.description().map { AttributedString(it) })
            }
        return builder.build()
    }

    /**
     * The picocli command named by the words before [lastIndex], or null when a word names no
     * subcommand. Words that start with `-` are options and are skipped.
     */
    private fun findCommandLine(
        words: List<String>,
        lastIndex: Int,
    ): CommandLine? {
        var current: CommandLine = root
        for (word in words.take(lastIndex)) {
            if (word.startsWith("-")) continue
            current = current.subcommands.values.firstOrNull {
                it.commandName == word || word in it.commandSpec.aliases()
            } ?: return null
        }
        return current
    }

    private fun optionCandidates(
        target: CommandLine,
        typed: String,
        candidates: MutableList<Candidate>,
    ) {
        val eq = typed.indexOf('=')
        val options = target.commandSpec.options().filterNot { it.hidden() }
        if (eq > 0) {
            valueCandidates(options, typed.substring(0, eq + 1), candidates)
            return
        }
        options.forEach { option ->
            val flag = option.arity().max() == 0
            option.names().forEach { name ->
                candidates.add(if (flag) candidate(name, name, true) else candidate("$name=", name, false))
            }
        }
    }

    /** The completion candidates of the option [prefix] names (`--name=`), each as `--name=value`. */
    private fun valueCandidates(
        options: List<CommandLine.Model.OptionSpec>,
        prefix: String,
        candidates: MutableList<Candidate>,
    ) {
        val option = options.firstOrNull { prefix.dropLast(1) in it.names() } ?: return
        option.completionCandidates()?.forEach { candidates.add(candidate(prefix + it, it, true)) }
    }

    private fun candidate(
        value: String,
        display: String,
        complete: Boolean,
    ) = Candidate(AttributedString.stripAnsi(value), display, null, null, null, null, complete)

    /** One top-level picocli subcommand, run through [root] so its execution strategy applies. */
    private inner class PicocliCommand(
        private val sub: CommandLine,
    ) : Command {
        private val name = sub.commandName

        override fun name(): String = name

        override fun aliases(): List<String> = sub.commandSpec.aliases().toList()

        override fun description(): String =
            sub.commandSpec
                .usageMessage()
                .description()
                .firstOrNull()
                .orEmpty()

        override fun describe(args: List<String>): CommandDescription? = this@ReplCommandGroup.describe(args)

        override fun execute(
            session: CommandSession,
            vararg args: String,
        ): Any = root.execute(name, *args)

        override fun completer(): Completer =
            Completer { _: LineReader, line: ParsedLine, candidates: MutableList<Candidate> ->
                complete(line, candidates)
            }
    }

    /** `cls` (alias `clear`): clears the terminal screen. */
    private class ClearScreen(
        private val terminal: Terminal,
    ) : Command {
        override fun name(): String = "cls"

        override fun aliases(): List<String> = listOf("clear")

        override fun description(): String = "Clears the screen"

        override fun execute(
            session: CommandSession,
            vararg args: String,
        ): Any? {
            terminal.puts(InfoCmp.Capability.clear_screen)
            terminal.flush()
            return null
        }
    }
}
