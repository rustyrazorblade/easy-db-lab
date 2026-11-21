package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.di.KoinCommandFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jline.reader.Candidate
import org.jline.reader.Parser
import org.jline.reader.impl.DefaultParser
import org.jline.shell.Shell
import org.jline.shell.impl.DefaultCommandDispatcher
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class ReplTest : BaseKoinTest() {
    @Command(name = "", subcommands = [Db::class, Info::class, Run::class, Secret::class])
    class Root : Runnable {
        override fun run() = Unit
    }

    @Command(name = "db", aliases = ["database"], description = ["Database commands"], subcommands = [DbStart::class])
    class Db : Runnable {
        override fun run() = Unit
    }

    @Command(name = "start", description = ["Start the database"])
    class DbStart : Runnable {
        @Option(names = ["--hosts"], description = ["Hosts to start"])
        var hosts: String? = null

        @Option(names = ["--force"], description = ["Skip checks"])
        var force: Boolean = false

        @Option(names = ["--mode"], completionCandidates = Modes::class)
        var mode: String? = null

        override fun run() = Unit
    }

    class Modes : Iterable<String> {
        override fun iterator(): Iterator<String> = listOf("fast", "safe").iterator()
    }

    @Command(name = "info", description = ["Show info"])
    class Info : Runnable {
        @Parameters(completionCandidates = Modes::class)
        var mode: String? = null

        override fun run() = Unit
    }

    /** Like `exec run`: the remaining words are a command for a remote shell. */
    @Command(name = "run", description = ["Run a remote command"])
    class Run : Runnable {
        @Option(names = ["--hosts"])
        var hosts: String? = null

        @Parameters
        var words: List<String> = emptyList()

        override fun run() = Unit
    }

    @Command(name = "secret", hidden = true)
    class Secret : Runnable {
        override fun run() = Unit
    }

    private val terminal: Terminal =
        TerminalBuilder
            .builder()
            .dumb(true)
            .streams(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
            .build()

    private val shells = mutableListOf<Shell>()
    private val terminals = mutableListOf<Terminal>()

    @AfterEach
    fun closeTerminal() {
        shells.forEach { it.close() }
        terminals.forEach { it.close() }
        terminal.close()
    }

    /** A root whose execution strategy records the deepest command and its parsed user object. */
    private class Recorded {
        val ran = mutableListOf<Any>()
        val root: CommandLine =
            CommandLine(Root()).also { cl ->
                cl.executionStrategy =
                    CommandLine.IExecutionStrategy { parseResult ->
                        var current = parseResult
                        while (current.subcommand() != null) current = current.subcommand()
                        ran.add(current.commandSpec().userObject())
                        EXIT_CODE
                    }
            }
    }

    private fun completions(
        group: ReplCommandGroup,
        line: String,
    ): List<String> {
        val candidates = mutableListOf<Candidate>()
        // The line reader parses for completion, which keeps the empty word after a trailing space.
        group.complete(DefaultParser().parse(line, line.length, Parser.ParseContext.COMPLETE), candidates)
        return candidates.map { it.value() }
    }

    /** A shell built as the REPL builds it (literal lines, help on), over [root], printing to [output]. */
    private fun replShell(
        root: CommandLine,
        output: ByteArrayOutputStream,
    ): Shell {
        val shellTerminal =
            TerminalBuilder
                .builder()
                .dumb(true)
                .streams(ByteArrayInputStream(ByteArray(0)), output)
                .build()
        terminals.add(shellTerminal)
        return Shell
            .builder()
            .terminal(shellTerminal)
            .dispatcher(LiteralLineDispatcher(shellTerminal))
            .groups(ReplCommandGroup(root, shellTerminal))
            .helpCommands(true)
            .build()
            .also { shells.add(it) }
    }

    @Test
    fun `a typed line runs through the root's execution strategy with its options parsed`() {
        val recorded = Recorded()
        val dispatcher = DefaultCommandDispatcher(terminal)
        dispatcher.addGroup(ReplCommandGroup(recorded.root, terminal))

        val result = dispatcher.execute("db start --hosts \"db0,db1\" --force")

        assertThat(result).isEqualTo(EXIT_CODE)
        val start = recorded.ran.single() as DbStart
        assertThat(start.hosts).isEqualTo("db0,db1")
        assertThat(start.force).isTrue()
    }

    @Test
    fun `an alias runs the command it names`() {
        val recorded = Recorded()
        val dispatcher = DefaultCommandDispatcher(terminal)
        dispatcher.addGroup(ReplCommandGroup(recorded.root, terminal))

        dispatcher.execute("database start")

        assertThat(recorded.ran.single()).isInstanceOf(DbStart::class.java)
    }

    @Test
    fun `a hidden command still runs when typed`() {
        val recorded = Recorded()
        val dispatcher = DefaultCommandDispatcher(terminal)
        dispatcher.addGroup(ReplCommandGroup(recorded.root, terminal))

        dispatcher.execute("secret")

        assertThat(recorded.ran.single()).isInstanceOf(Secret::class.java)
    }

    @Test
    fun `the group offers each top-level command once under its own name, plus cls`() {
        val group = ReplCommandGroup(Recorded().root, terminal)

        val commands = group.commands().associateBy { it.name() }

        assertThat(commands.keys).containsExactlyInAnyOrder("db", "info", "run", "cls")
        assertThat(commands.getValue("db").aliases()).containsExactly("database")
        assertThat(commands.getValue("db").description()).isEqualTo("Database commands")
        assertThat(commands.getValue("cls").aliases()).containsExactly("clear")
    }

    @Test
    fun `a hidden top-level command is not offered for the first word or listed by help`() {
        val shell = replShell(Recorded().root, ByteArrayOutputStream())
        val firstWord = mutableListOf<Candidate>()

        shell.dispatcher().completer().complete(shell.reader(), DefaultParser().parse("", 0, Parser.ParseContext.COMPLETE), firstWord)
        // help prints to the session stream; capture it directly, not through the terminal, whose
        // output path depends on the terminal type the environment selects.
        val helpOut = ByteArrayOutputStream()
        (shell.dispatcher() as LiteralLineDispatcher).session().setOut(PrintStream(helpOut, true))
        shell.dispatcher().execute("help")

        assertThat(firstWord.map { it.value() }).contains("db", "info", "cls").doesNotContain("secret")
        val helpRows = helpOut.toString().lines().map { it.trim().substringBefore(" ") }
        assertThat(helpRows).contains("db", "info", "cls").doesNotContain("secret")
    }

    @Test
    fun `a line reaches the command literally, with no local expansion or shell operators`() {
        val recorded = Recorded()
        val dispatcher = replShell(recorded.root, ByteArrayOutputStream()).dispatcher()

        dispatcher.execute("run --hosts db0 \"echo \$HOME\" ~/.bashrc | grep x && ls > out ; date &")

        val run = recorded.ran.single() as Run
        assertThat(run.hosts).isEqualTo("db0")
        assertThat(run.words)
            .containsExactly("echo \$HOME", "~/.bashrc", "|", "grep", "x", "&&", "ls", ">", "out", ";", "date", "&")
    }

    @Test
    fun `a NAME=value word is passed to the command, not stored as a shell variable`() {
        val recorded = Recorded()
        val dispatcher = replShell(recorded.root, ByteArrayOutputStream()).dispatcher() as LiteralLineDispatcher

        dispatcher.execute("run FOO=bar")

        assertThat((recorded.ran.single() as Run).words).containsExactly("FOO=bar")
        assertThat(dispatcher.session().get("FOO")).isNull()
        assertThatThrownBy { dispatcher.execute("FOO=bar") }.hasMessageContaining("FOO=bar")
        assertThat(dispatcher.session().get("FOO")).isNull()
    }

    @Test
    fun `completion offers the subcommands of the command typed so far`() {
        val group = ReplCommandGroup(Recorded().root, terminal)

        assertThat(completions(group, "db ")).containsExactly("start")
        assertThat(completions(group, "database ")).containsExactly("start")
    }

    @Test
    fun `completion offers options, with = for an option that takes a value`() {
        val group = ReplCommandGroup(Recorded().root, terminal)

        assertThat(completions(group, "db start --"))
            .contains("--hosts=", "--force", "--mode=")
            .doesNotContain("--hosts", "--force=")
    }

    @Test
    fun `completion after option= offers the option's completion candidates`() {
        val group = ReplCommandGroup(Recorded().root, terminal)

        assertThat(completions(group, "db start --mode=")).containsExactlyInAnyOrder("--mode=fast", "--mode=safe")
    }

    @Test
    fun `completion under an unknown or hidden command offers nothing`() {
        val group = ReplCommandGroup(Recorded().root, terminal)

        assertThat(completions(group, "nope ")).isEmpty()
        assertThat(completions(group, "secret ")).isEmpty()
    }

    @Test
    fun `an option value given as a separate word does not end completion or the description`() {
        val group = ReplCommandGroup(Recorded().root, terminal)

        assertThat(completions(group, "db start --hosts db0 --")).contains("--force", "--mode=")
        assertThat(completions(group, "db start --mode ")).containsExactlyInAnyOrder("fast", "safe")
        // A word that names a known option, after a value-taking option, is that option, as picocli reads it.
        assertThat(completions(group, "db start --hosts --f")).contains("--force")
        assertThat(completions(group, "db start --hosts --mode ")).containsExactlyInAnyOrder("fast", "safe")
        assertThat(group.describe(listOf("db", "start", "--hosts", "db0"))!!.mainDescription().map { it.toString() })
            .containsExactly("Start the database")
    }

    @Test
    fun `completion offers a positional parameter's completion candidates`() {
        val group = ReplCommandGroup(Recorded().root, terminal)

        assertThat(completions(group, "info ")).containsExactlyInAnyOrder("fast", "safe")
    }

    @Test
    fun `a word that names no subcommand stops the walk, and later words are not read as subcommands`() {
        val group = ReplCommandGroup(Recorded().root, terminal)

        assertThat(completions(group, "db nope ")).isEmpty()
        assertThat(group.describe(listOf("db", "nope", "start"))!!.mainDescription().map { it.toString() })
            .containsExactly("Database commands")
        assertThat(group.describe(listOf("nope"))).isNull()
    }

    @Test
    fun `the description is the deepest named command's, with its options`() {
        val group = ReplCommandGroup(Recorded().root, terminal)

        val description = group.describe(listOf("db", "start"))!!

        assertThat(description.isCommand).isTrue()
        assertThat(description.mainDescription().map { it.toString() }).containsExactly("Start the database")
        assertThat(description.options().keys).contains("--hosts=<hosts>", "--force")
    }

    @Test
    fun `the REPL tree reaches nested commands from the real command set`() {
        val group = ReplCommandGroup(CommandLine(ShellCommands::class.java, KoinCommandFactory()), terminal)

        assertThat(group.commands().map { it.name() })
            .contains("version", "status", "init", "up", "cassandra", "spark", "opensearch", "aws", "cls")
        assertThat(completions(group, "cassandra ")).contains("start", "stop", "restart", "stress")
        assertThat(completions(group, "cassandra stress ")).contains("start", "stop", "status")
    }

    private companion object {
        const val EXIT_CODE = 7
    }
}
