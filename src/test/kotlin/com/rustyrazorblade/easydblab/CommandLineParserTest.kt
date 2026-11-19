package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.commands.Status
import com.rustyrazorblade.easydblab.commands.profile.Profile
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.CommandFailureReporter
import com.rustyrazorblade.easydblab.services.DefaultHelpTopicService
import com.rustyrazorblade.easydblab.services.DefaultKitCommandScanner
import com.rustyrazorblade.easydblab.services.HelpTopicService
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitCommandScanner
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import com.rustyrazorblade.easydblab.services.WorkspaceKitScanner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Proves that `easy-db-lab --help` lists exactly the workspace directories holding a `kit.yaml`.
 *
 * This is the end-to-end guard for the registration path. `WorkspaceKitScannerTest` proves the
 * rule; this proves the parser actually applies it, so re-inlining a `bin/` predicate or swapping
 * the scanner for a local `listFiles()` walk fails a test instead of silently restoring the
 * defect that flooded help output with Cassandra checkouts.
 *
 * Every collaborator is the real implementation. All three read the filesystem or the classpath
 * and have no external side effects, and the whole point of the test is the wiring between them.
 */
class CommandLineParserTest : BaseKoinTest() {
    private val stdout = ByteArrayOutputStream()
    private val originalOut = System.out

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { WorkspaceKitScanner(get()) }
                single { KitSourcesProvider(get()) }
                single { InstallTemplateResolver(get(), get()) }
                single<KitCommandScanner> { DefaultKitCommandScanner() }
                single { ClusterStateManager(File(get<Context>().workingDirectory, "state.json")) }
                single<HelpTopicService> { DefaultHelpTopicService() }
                single { CommandFailureReporter(get(), emptyList()) }
            },
        )

    @BeforeEach
    fun captureStdout() {
        System.setOut(PrintStream(stdout))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
        stdout.reset()
    }

    @Test
    fun `help lists a directory holding a kit descriptor and omits a bin-only checkout`() {
        val workspace = context.workingDirectory
        File(workspace, "clickhouse").mkdirs()
        File(File(workspace, "clickhouse"), Constants.Kit.CONFIG_FILE).writeText("name: clickhouse\n")
        File(File(workspace, "trunk"), "bin").mkdirs()
        File(File(File(workspace, "trunk"), "bin"), "cassandra").also {
            it.writeText("#!/bin/bash\n")
            it.setExecutable(true)
        }

        // --help exits 0, so eval() returns rather than reaching exitProcess.
        CommandLineParser().eval(arrayOf("--help"))

        val output = stdout.toString()
        assertThat(output).contains("clickhouse")
        assertThat(output).doesNotContain("trunk")
    }

    @Test
    fun `a failing non-PicoCommand is reported through the failure reporter with a non-zero exit`() {
        val events = mutableListOf<Event>()
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events.add(envelope.event)
                }

                override fun close() {
                    // Nothing to release.
                }
            },
        )
        val parser = CommandLineParser()
        parser.commandLine.addSubcommand("boom", BoomCommand())

        val exitCode = parser.commandLine.execute("boom")

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(events.filterIsInstance<Event.Command.ExecutionError>().single().error)
            .contains("IllegalStateException")
            .contains("boom happened")
    }

    @Test
    fun `an interrupt reaching the picocli handler keeps the interrupt flag and is not reported`() {
        val events = mutableListOf<Event>()
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events.add(envelope.event)
                }

                override fun close() {
                    // Nothing to release.
                }
            },
        )
        val parser = CommandLineParser()
        parser.commandLine.addSubcommand("stop", StopCommand())

        try {
            val exitCode = parser.commandLine.execute("stop")

            assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
        assertThat(events.filterIsInstance<Event.Command.ExecutionError>()).isEmpty()
    }

    @Test
    fun `a workspace directory named profile does not shadow the profile command group`() {
        // registerDynamicKitSubcommands() drops a discovered kit whose name collides with a static
        // top-level command. Without that guard a directory named "profile" replaces the command
        // group with a kit runner, and `profile show` stops resolving.
        val kitDir = File(context.workingDirectory, "profile")
        kitDir.mkdirs()
        File(kitDir, Constants.Kit.CONFIG_FILE).writeText("name: profile\n")

        val commandLine = CommandLineParser().commandLine
        val profileGroup = commandLine.subcommands.getValue("profile")

        assertThat(profileGroup.commandSpec.userObject()).isInstanceOf(Profile::class.java)
        assertThat(profileGroup.subcommands.keys).contains("show", "setup")
    }

    @Test
    fun `a workspace kit named after a built-in command is reported and the built-in stays`() {
        val kitDir = File(context.workingDirectory, "cassandra")
        kitDir.mkdirs()
        File(kitDir, Constants.Kit.CONFIG_FILE).writeText("name: cassandra\n")
        val events = recordEvents()

        val commandLine = CommandLineParser().commandLine

        assertThat(events.filterIsInstance<Event.Kit.NameCollision>())
            .containsExactly(Event.Kit.NameCollision(kit = "cassandra", command = "cassandra"))
        assertThat(
            commandLine.subcommands
                .getValue("cassandra")
                .subcommands.keys,
        ).contains("start")
    }

    @Test
    fun `a workspace kit that does not collide registers with no collision event`() {
        val kitDir = File(context.workingDirectory, "cassandra-pods")
        kitDir.mkdirs()
        File(kitDir, Constants.Kit.CONFIG_FILE).writeText("name: cassandra-pods\n")
        val events = recordEvents()

        val commandLine = CommandLineParser().commandLine

        assertThat(commandLine.subcommands).containsKey("cassandra-pods")
        assertThat(events.filterIsInstance<Event.Kit.NameCollision>()).isEmpty()
    }

    @Test
    fun `the built-in command names are exactly the top-level commands of a workspace with no kits`() {
        val commandLine = CommandLineParser().commandLine

        // Names and aliases both: a kit named like an alias is just as unreachable. Fails when a
        // command is added to the tree by code (addSubcommand) rather than the @Command annotation.
        val registered =
            commandLine.subcommands.values
                .flatMap { listOf(it.commandName) + it.commandSpec.aliases().toList() }
                .toSet()
        assertThat(BuiltInCommands.names).containsAll(registered)
    }

    private fun recordEvents(): List<Event> {
        val events = mutableListOf<Event>()
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events.add(envelope.event)
                }

                override fun close() = Unit
            },
        )
        return events
    }

    @Test
    fun `the status command is instantiated with its defaulted capabilities by the CLI factory`() {
        val status =
            CommandLineParser()
                .commandLine.subcommands
                .getValue("status")
                .commandSpec
                .userObject()

        assertThat(status).isInstanceOf(Status::class.java)
    }

    @Test
    fun `version flag prints the build version`() {
        System.setProperty("easydblab.version", "42")
        try {
            CommandLineParser().eval(arrayOf("--version"))
        } finally {
            System.clearProperty("easydblab.version")
        }

        assertThat(stdout.toString().trim()).isEqualTo("42")
    }

    @Test
    fun `version flag on a subcommand prints the build version`() {
        System.setProperty("easydblab.version", "42")
        try {
            CommandLineParser().eval(arrayOf("hosts", "--version"))
        } finally {
            System.clearProperty("easydblab.version")
        }

        assertThat(stdout.toString().trim()).isEqualTo("42")
    }

    @Test
    fun `root usage includes a footer pointing to the help topic system`() {
        CommandLineParser().eval(arrayOf("--help"))

        val output = stdout.toString()
        assertThat(output).contains("Run 'easy-db-lab help' to list task-oriented guides.")
    }

    @Test
    fun `a command mapping to a topic includes a footer naming its related help topic`() {
        // cassandra is both a command group and a help topic.
        val commandLine = CommandLineParser().commandLine
        val cassandraGroup = commandLine.subcommands.getValue("cassandra")

        // Request usage for the cassandra command.
        stdout.reset()
        cassandraGroup.usage(System.out)

        val output = stdout.toString()
        assertThat(output).contains("See 'easy-db-lab help cassandra' for a task-oriented guide.")
    }

    @Test
    fun `footer text is derived from the discovered topic set not a hardcoded list`() {
        // If wireHelpFooters() hardcoded topic names, this test would fail when a new topic is
        // added. By verifying that the topic scan drives the footer, we prove the pointers
        // track the packaged topic set automatically.
        val commandLine = CommandLineParser().commandLine
        val helpTopicService = getKoin().get<HelpTopicService>()
        val topics = helpTopicService.findAll()

        // For each topic, if a matching top-level command exists, that command's footer should
        // name the topic.
        val topicNames = topics.map { it.name }.toSet()
        val commandsWithTopics =
            commandLine.subcommands.filter { (name, _) -> name in topicNames }

        // Verify at least one command-to-topic match exists (e.g., cassandra, spark).
        assertThat(commandsWithTopics).isNotEmpty

        commandsWithTopics.forEach { (name, subCmd) ->
            val footer = subCmd.commandSpec.usageMessage().footer()
            assertThat(footer).isNotEmpty
            assertThat(footer.joinToString(" ")).contains("help $name")
        }
    }

    /** Metrics and logs are stored by Mimir and Loki; the snapshot commands went with VictoriaMetrics and VictoriaLogs. */
    @Test
    fun `the metrics and logs snapshot commands are unknown, and logs query remains`() {
        val commandLine = CommandLineParser().commandLine

        for (args in listOf("metrics backup", "metrics import", "metrics ls", "logs backup", "logs import", "logs ls")) {
            assertThatThrownBy { commandLine.parseArgs(*args.split(" ").toTypedArray()) }
                .describedAs(args)
                .isInstanceOf(CommandLine.UnmatchedArgumentException::class.java)
        }
        assertThat(
            commandLine.subcommands
                .getValue("logs")
                .subcommands.keys,
        ).containsExactly("query")
    }

    /** The SOCKS tunnel is managed by the `socks` group; the old top-level names are gone, with no aliases. */
    @Test
    fun `socks start and socks stop parse, and start-socks and stop-socks are unknown`() {
        val commandLine = CommandLineParser().commandLine

        assertThat(
            commandLine.subcommands
                .getValue("socks")
                .subcommands.keys,
        ).containsExactlyInAnyOrder("start", "stop")
        for (args in listOf("start-socks", "stop-socks")) {
            assertThatThrownBy { commandLine.parseArgs(args) }
                .describedAs(args)
                .isInstanceOf(CommandLine.UnmatchedArgumentException::class.java)
        }
    }

    /**
     * `cassandra download-config --help` failed with "Unknown option": the command owns `--version`,
     * which blocked picocli's standard help options.  Every command in the tree must answer `--help`
     * with a help request, and the ones that own `--version` must keep it as a value option.
     */
    @Test
    fun `every command in the tree accepts help, including those that own a version option`() {
        fun leaves(cl: CommandLine): List<CommandLine> =
            listOf(cl) +
                cl.subcommands.values
                    .distinct()
                    .flatMap { leaves(it) }

        val all = leaves(CommandLineParser().commandLine)
        val ownVersion = all.filter { it.commandSpec.findOption("--version")?.versionHelp() == false }
        assertThat(ownVersion.map { it.commandSpec.qualifiedName() })
            .contains("easy-db-lab cassandra download-config", "easy-db-lab cassandra update-config")

        all.forEach { cl ->
            val result = cl.parseArgs("--help")
            assertThat(result.isUsageHelpRequested).describedAs(cl.commandSpec.qualifiedName()).isTrue()
        }
    }

    /** `commands` and `--help` print each option's description; an empty one tells the user nothing. */
    @Test
    fun `every visible option in the command tree has a description`() {
        fun undescribed(cl: CommandLine): List<String> =
            cl.commandSpec
                .options()
                .filter { !it.hidden() && it.description().joinToString("").isBlank() }
                .map { "${cl.commandSpec.qualifiedName()} ${it.longestName()}" } +
                cl.subcommands.values
                    .distinct()
                    .flatMap { undescribed(it) }

        assertThat(undescribed(CommandLineParser().commandLine)).isEmpty()
    }

    /**
     * A kit whose kit.yaml defines one option twice made picocli throw while the command tree was
     * built, so every command failed, `kit source remove` included. One bad kit must cost only that
     * kit.
     */
    @Test
    fun `a kit with a duplicate option is skipped with a warning and the other commands still load`() {
        writeProfileKit(
            "dup-kit",
            """
            name: dup-kit
            args:
              - flag: --example-option
                variable: EXAMPLE_ONE
                type: string
                description: first
              - flag: --example-option
                variable: EXAMPLE_TWO
                type: string
                description: second
            """.trimIndent(),
        )
        writeProfileKit(
            "good-kit",
            """
            name: good-kit
            args:
              - flag: --example-option
                variable: EXAMPLE
                type: string
                description: fine
            """.trimIndent(),
        )

        val commandLine = CommandLineParser().commandLine

        val kitGroup = commandLine.subcommands.getValue("kit")
        val installed =
            kitGroup.subcommands
                .getValue("install")
                .subcommands.keys
        assertThat(installed).contains("good-kit").doesNotContain("dup-kit")
        assertThat(
            kitGroup.subcommands
                .getValue("source")
                .subcommands.keys,
        ).contains("list", "remove")

        val warnings = getKoin().get<OutputHandler>() as BufferedOutputHandler
        assertThat(warnings.errors.map { it.first }.joinToString("\n"))
            .contains("dup-kit", "kit.yaml", "--example-option")
    }

    @Test
    fun `a kit with an unparseable kit yaml is skipped with a warning naming the file`() {
        writeProfileKit("broken-kit", "name: [unterminated\n")

        val commandLine = CommandLineParser().commandLine

        val installed =
            commandLine.subcommands
                .getValue("kit")
                .subcommands
                .getValue("install")
                .subcommands.keys
        assertThat(installed).doesNotContain("broken-kit")
        val warnings = getKoin().get<OutputHandler>() as BufferedOutputHandler
        assertThat(warnings.errors.map { it.first }.joinToString("\n")).contains("broken-kit", "kit.yaml")
    }

    private fun writeProfileKit(
        name: String,
        kitYaml: String,
    ) {
        val dir = File(File(context.profileDir, "kits"), name).apply { mkdirs() }
        File(dir, Constants.Kit.CONFIG_FILE).writeText(kitYaml)
    }

    /** The cluster name is positional. `--name` used to be rejected with only "Unknown option". */
    @Test
    fun `init --name is refused with the positional form`() {
        val commandLine = CommandLineParser().commandLine

        assertThatThrownBy { commandLine.parseArgs("init", "--name", "my-cluster", "--db", "3") }
            .isInstanceOf(CommandLine.ParameterException::class.java)
            .hasMessageContaining("easy-db-lab init <name>")
    }

    /** A plain Picocli command that is interrupted, as Ctrl-C or SIGTERM does to a REPL or server thread. */
    @CommandLine.Command(name = "stop")
    class StopCommand : Runnable {
        override fun run(): Unit = throw InterruptedException("stopped")
    }

    /** A plain Picocli command, not a PicoCommand, so the executor never sees it. */
    @CommandLine.Command(name = "boom")
    class BoomCommand : Runnable {
        override fun run(): Unit = throw IllegalStateException("boom happened")
    }
}
