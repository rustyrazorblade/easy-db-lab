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

    /**
     * The visible top-level commands, plus `cls`. jline lists these in `help` and offers them for
     * the first word, so a hidden command is left out; [command] still finds it when it is typed.
     */
    override fun commands(): Collection<Command> =
        root.subcommands.values
            .distinct()
            .filterNot { it.commandSpec.usageMessage().hidden() }
            .map { PicocliCommand(it) } + ClearScreen(terminal)

    /** The command [name] names, hidden or not, so a hidden command runs when typed. */
    override fun command(name: String): Command? =
        root.subcommands[name]?.let { PicocliCommand(it) } ?: ClearScreen(terminal).takeIf { name == it.name() || name in it.aliases() }

    /** Completes the line by finding the picocli command its finished words name. */
    fun complete(
        line: ParsedLine,
        candidates: MutableList<Candidate>,
    ) {
        val position = locate(line.words(), line.wordIndex())
        if (position.command.commandSpec
                .usageMessage()
                .hidden()
        ) {
            return
        }
        val word = line.word()
        val pending = position.pendingOption
        when {
            word.startsWith("-") -> optionCandidates(position.command, word.substring(0, line.wordCursor()), candidates)
            pending != null -> pending.completionCandidates()?.forEach { candidates.add(candidate(it, it, true)) }
            else -> wordCandidates(position, candidates)
        }
    }

    /** The subcommands (unless a positional was already given) and the positional candidates. */
    private fun wordCandidates(
        position: Position,
        candidates: MutableList<Candidate>,
    ) {
        val spec = position.command.commandSpec
        if (!position.positionalSeen) {
            position.command.subcommands.values
                .distinct()
                .filterNot { it.commandSpec.usageMessage().hidden() }
                .flatMap { listOf(it.commandSpec.name()) + it.commandSpec.aliases() }
                .forEach { candidates.add(candidate(it, it, complete = true)) }
        }
        spec
            .positionalParameters()
            .filterNot { it.hidden() }
            .flatMap { it.completionCandidates()?.toList().orEmpty() }
            .forEach { candidates.add(candidate(it, it, complete = true)) }
    }

    /**
     * The tail-tip description of the deepest command [args] name: its description and options.
     * Null when the first word names no command.
     */
    fun describe(args: List<String>): CommandDescription? {
        val target = locate(args, args.size).command
        if (target === root) return null
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
     * Where the words before [lastIndex] leave the line.
     *
     * @property command the deepest command the words name.
     * @property pendingOption the option whose value comes next: the last word was `--opt`, with no
     *   `=`, for an option that takes a value.
     * @property positionalSeen a word that names no subcommand was given, so no subcommand can follow.
     */
    private class Position(
        val command: CommandLine,
        val pendingOption: CommandLine.Model.OptionSpec?,
        val positionalSeen: Boolean,
    )

    /**
     * Walks the words before [lastIndex] down the picocli tree. An option is skipped, with its value
     * when it is given as a separate word (`--hosts db0`). As in picocli, the word after a
     * value-taking option is its value unless it names a known option, so `--offset -5` keeps `-5`
     * as the value. The walk stops descending at the first word that names no subcommand.
     */
    private fun locate(
        words: List<String>,
        lastIndex: Int,
    ): Position {
        var current: CommandLine = root
        var pending: CommandLine.Model.OptionSpec? = null
        var positionalSeen = false
        for (word in words.take(lastIndex)) {
            val valueOf = pending
            pending = null
            val sub = current.subcommands[word]
            val option = if (word.startsWith("-")) current.commandSpec.findOption(word.substringBefore('=')) else null
            when {
                valueOf != null && option == null -> Unit
                word.startsWith("-") -> pending = option?.takeIf { '=' !in word && it.arity().min() > 0 }
                positionalSeen || sub == null -> positionalSeen = true
                else -> current = sub
            }
        }
        return Position(current, pending, positionalSeen)
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
