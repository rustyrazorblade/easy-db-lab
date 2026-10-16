package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.services.ObservabilityResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

/**
 * One temporary compose project of a query export: its files written to its directory, started with
 * `docker compose -p <name> up -d`, each backend found on the loopback port compose published for it
 * and waited for until `/ready` answers, then stopped and removed.
 */
class DockerExportStackTest {
    @TempDir
    lateinit var dir: File

    private val commands = mutableListOf<List<String>>()
    private var upResult = ComposeResult(0, "")
    private val compose =
        ComposeCli { _, project, args, _ ->
            commands += listOf(project) + args
            when (args.first()) {
                "port" -> ComposeResult(0, "127.0.0.1:${40000 + args[2].toInt() % 1000}\n")
                "up" -> upResult
                else -> ComposeResult(0, "")
            }
        }
    private var readyAfter = 2
    private val portsSeen = mutableListOf<Map<Int, Int>>()
    private val backends =
        FakeBackends { call ->
            if (call.route == "/ready" && readyAfter-- > 0) ObservabilityResponse(503, "not ready") else ObservabilityResponse(200, "ready")
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
                http = { tenant, ports ->
                    portsSeen += ports
                    backends.forTenant(tenant)
                },
                readyTimeout = Duration.ofSeconds(5),
                pollInterval = Duration.ofMillis(1),
            ),
        )

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

    @Test
    fun `a service is stopped gracefully, and closing removes the project`() {
        val stack = start()

        stack.stop("loki")
        stack.close()

        assertThat(commands).contains(listOf("edl-export-1-reader", "stop", "-t", "120", "loki"))
        assertThat(commands.last()).containsExactly("edl-export-1-reader", "down", "--remove-orphans", "-t", "60")
    }

    @Test
    fun `a project that does not start fails with compose's output and is removed`() {
        upResult = ComposeResult(1, "service store-check didn't complete successfully: exit 1")

        assertThatThrownBy { start() }.hasMessageContaining("store-check")
        assertThat(commands.last()).contains("down")
    }

    @Test
    fun `a backend that never becomes ready fails naming it, and the project is removed`() {
        readyAfter = Int.MAX_VALUE

        assertThatThrownBy { start() }.hasMessageContaining("mimir")
        assertThat(commands.last()).contains("down")
    }
}
