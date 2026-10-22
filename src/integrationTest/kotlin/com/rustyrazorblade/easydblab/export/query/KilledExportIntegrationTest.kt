package com.rustyrazorblade.easydblab.export.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A query export killed with SIGTERM (what Ctrl-C's terminal and `kill` send the JVM) left its reader
 * and writer compose projects running: seven containers nobody removed. A child JVM starts a real
 * temporary stack through [DockerExportStack] ([KilledExportHarness]), is sent SIGTERM once the stack
 * is up, and must leave no container of the project and its saved `compose.log` behind.
 */
class KilledExportIntegrationTest {
    @TempDir
    lateinit var dir: File

    private val project = "edl-export-kill-${UUID.randomUUID().toString().take(8)}"
    private val timeout = Duration.ofMinutes(1)

    private fun containers(): String =
        DockerComposeCli
            .run(dir, project, listOf("ps", "-a", "-q"), timeout)
            .also { check(it.exitCode == 0) { it.output } }
            .output
            .trim()

    @AfterEach
    fun removeLeftovers() {
        DockerComposeCli.run(dir, project, listOf("down", "--remove-orphans", "-t", "0"), timeout)
    }

    @Test
    fun `SIGTERM to an export whose stack is up removes the stack, saving its logs first`() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val child =
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), KilledExportHarness::class.java.name, dir.path, project)
                .redirectErrorStream(true)
                .start()
        val output = StringBuilder()
        val up = CompletableFuture<Unit>()
        Thread {
            child.inputStream.bufferedReader().forEachLine { line ->
                synchronized(output) { output.appendLine(line) }
                if (line == KilledExportHarness.UP) up.complete(Unit)
            }
        }.apply { isDaemon = true }.start()

        try {
            up.get(STARTUP_MINUTES, TimeUnit.MINUTES)
        } catch (e: Exception) {
            child.destroyForcibly()
            throw AssertionError("The harness did not start its stack:\n${synchronized(output) { output }}", e)
        }
        assertThat(containers()).isNotEmpty()

        // SIGTERM on Unix.
        child.destroy()
        assertThat(child.waitFor(EXIT_MINUTES, TimeUnit.MINUTES)).describedAs("the killed JVM exits").isTrue()

        assertThat(containers()).describedAs("containers of $project after SIGTERM").isEmpty()
        assertThat(File(dir, "compose.log")).isFile()
        assertThat(File(dir, "compose.log").readText()).contains("web")
    }

    private companion object {
        const val STARTUP_MINUTES = 5L
        const val EXIT_MINUTES = 3L
    }
}
