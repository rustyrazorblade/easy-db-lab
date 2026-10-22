package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.ObservabilityResponse
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Duration

/**
 * One temporary compose project of a query export: its files written to its directory, started with
 * `docker compose -p <name> up -d`, each backend found on the loopback port compose published for it
 * and waited for until `/ready` answers, then its logs saved beside it and the project removed. A
 * project that cannot be removed is reported with the command that removes it.
 */
class DockerExportStackTest {
    @TempDir
    lateinit var dir: File

    private val commands = mutableListOf<List<String>>()
    private var upResult = ComposeResult(0, "")
    private var downResult = ComposeResult(0, "")
    private val compose =
        ComposeCli { _, project, args, _ ->
            commands += listOf(project) + args
            when (args.first()) {
                "port" -> ComposeResult(0, "127.0.0.1:${40000 + args[2].toInt() % 1000}\n")
                "up" -> upResult
                "logs" -> ComposeResult(0, "2026-10-16T10:00:00Z mimir | the log line\n")
                "down" -> downResult
                else -> ComposeResult(0, "")
            }
        }
    private var readyAfter = 2
    private var readyFailure: Exception? = null
    private val portsSeen = mutableListOf<Map<Int, Int>>()
    private val clientsMade = mutableListOf<OkHttpClient>()
    private val clientsSeen = mutableListOf<OkHttpClient>()
    private val backends =
        FakeBackends { call ->
            readyFailure?.let { throw it }
            if (call.route == "/ready" && readyAfter-- > 0) ObservabilityResponse(503, "not ready") else ObservabilityResponse(200, "ready")
        }
    private val events = mutableListOf<Event>()

    /** The JVM's shutdown hooks, as a test sees them: registered, run by hand, removed. */
    private val hooks =
        object : ShutdownHooks {
            val registered = mutableListOf<Thread>()

            override fun add(hook: Thread) {
                registered += hook
            }

            override fun remove(hook: Thread) {
                registered -= hook
            }
        }
    private val eventBus =
        EventBus().apply {
            addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events += envelope.event
                    }

                    override fun close() = Unit
                },
            )
        }

    private fun start(): ExportStack =
        DockerExportStack.start(
            dir,
            StackDefinition(
                project = "edl-export-1-reader",
                files = mapOf("compose.yml" to "services: {}".toByteArray(), "config/x/y.conf" to "y".toByteArray()),
                published = mapOf("mimir" to listOf(9009), "loki" to listOf(3100)),
            ),
            StackRuntime(
                compose = compose,
                eventBus = eventBus,
                client = { OkHttpClient().also { clientsMade += it } },
                http = { tenant, ports, client ->
                    portsSeen += ports
                    clientsSeen += client
                    backends.forTenant(tenant)
                },
                readyTimeout = Duration.ofSeconds(3),
                pollInterval = Duration.ofMillis(1),
                shutdownHooks = hooks,
            ),
        )

    private val logFile get() = File(dir, "compose.log")

    @Test
    fun `the files are written, the project started detached, and each backend reached on its published port once ready`() {
        val stack = start()

        assertThat(File(dir, "config/x/y.conf").readText()).isEqualTo("y")
        assertThat(commands.first()).containsExactly("edl-export-1-reader", "up", "-d")
        assertThat(portsSeen.last()).containsEntry(9009, 40009).containsEntry(3100, 40100)
        assertThat(backends.calls.filter { it.route == "/ready" }.map { it.port }).contains(9009, 3100)
        stack.http("acme")
        assertThat(backends.calls).allMatch { it.route == "/ready" }
    }

    /** A client per request left a socket idle for five minutes each: a large trace export ran out of file descriptors. */
    @Test
    fun `every request of a stack goes through one client, whose connections are released when the stack is closed`() {
        val stack = start()
        repeat(3) { stack.http("acme") }
        stack.http("default")

        assertThat(clientsMade).hasSize(1)
        assertThat(clientsSeen).isNotEmpty().allMatch { it === clientsMade.single() }
        stack.close()
        assertThat(
            clientsMade
                .single()
                .dispatcher.executorService.isShutdown,
        ).isTrue()
        assertThat(clientsMade.single().connectionPool.connectionCount()).isZero()
    }

    @Test
    fun `a service is stopped gracefully, and closing saves the project's logs beside it, then removes it`() {
        val stack = start()

        stack.stop("loki")
        stack.close()

        assertThat(commands).contains(listOf("edl-export-1-reader", "stop", "-t", "120", "loki"))
        assertThat(commands.takeLast(2)).containsExactly(
            listOf("edl-export-1-reader", "logs", "--no-color", "--timestamps"),
            listOf("edl-export-1-reader", "down", "--remove-orphans", "-t", "60"),
        )
        assertThat(logFile.readText()).contains("mimir | the log line")
        assertThat(events).isEmpty()
    }

    /** A killed export (Ctrl-C, SIGTERM) left both stacks' seven containers running. */
    @Test
    fun `a stack that is up has a shutdown hook that saves its logs and removes it, the way closing does`() {
        start()
        val hook = hooks.registered.single()
        commands.clear()

        hook.run()

        assertThat(commands).containsExactly(
            listOf("edl-export-1-reader", "logs", "--no-color", "--timestamps"),
            listOf("edl-export-1-reader", "down", "--remove-orphans", "-t", "60"),
        )
        assertThat(logFile.readText()).contains("mimir | the log line")
    }

    @Test
    fun `closing a stack removes its shutdown hook, and a hook that already removed it leaves closing nothing to do`() {
        val closed = start()
        closed.close()
        assertThat(hooks.registered).isEmpty()

        val killed = start()
        hooks.registered.single().run()
        commands.clear()
        killed.close()

        assertThat(commands).isEmpty()
        assertThat(hooks.registered).isEmpty()
    }

    @Test
    fun `a project that does not start leaves no shutdown hook behind`() {
        upResult = ComposeResult(1, "store-check exited 1")

        assertThatThrownBy { start() }

        assertThat(hooks.registered).isEmpty()
    }

    @Test
    fun `a project that does not start fails with compose's output, naming its saved logs, and is removed`() {
        upResult = ComposeResult(1, "service store-check didn't complete successfully: exit 1")

        assertThatThrownBy { start() }.hasMessageContaining("store-check").hasMessageContaining(logFile.path)
        assertThat(logFile.readText()).contains("mimir | the log line")
        assertThat(commands.last()).contains("down")
        assertThat(
            clientsMade
                .single()
                .dispatcher.executorService.isShutdown,
        ).isTrue()
    }

    @Test
    fun `a backend that never becomes ready fails naming it and its last answer, and the project is removed`() {
        readyAfter = Int.MAX_VALUE

        assertThatThrownBy { start() }.hasMessageContaining("mimir").hasMessageContaining("503").hasMessageContaining("not ready")
        assertThat(commands.last()).contains("down")
    }

    @Test
    fun `a backend that cannot be reached fails naming the last error`() {
        readyFailure = IOException("Connection refused")

        assertThatThrownBy { start() }.hasMessageContaining("mimir").hasMessageContaining("Connection refused")
    }

    @Test
    fun `a project compose cannot remove is reported with the command that removes it, and closing still succeeds`() {
        val stack = start()
        downResult = ComposeResult(1, "network in use")

        stack.close()

        val left = events.filterIsInstance<Event.Export.StackNotRemoved>().single()
        assertThat(left).isEqualTo(Event.Export.StackNotRemoved("edl-export-1-reader", dir.path, 1, "network in use"))
        assertThat(left.toDisplayString()).contains("docker compose -p edl-export-1-reader down --remove-orphans", dir.path)
        assertThat(left.isError()).isTrue()
    }

    @Test
    fun `a project that neither starts nor is removed fails with the start's error, the removal's attached and reported`() {
        upResult = ComposeResult(1, "port is already allocated")
        downResult = ComposeResult(1, "network in use")

        assertThatThrownBy { start() }
            .hasMessageContaining("port is already allocated")
            .satisfies({ thrown -> assertThat(thrown.suppressed.map { it.message }).anyMatch { it!!.contains("network in use") } })
        assertThat(events.filterIsInstance<Event.Export.StackNotRemoved>().single().exitCode).isEqualTo(1)
    }
}
