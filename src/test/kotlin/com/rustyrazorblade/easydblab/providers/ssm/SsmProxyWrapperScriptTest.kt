package com.rustyrazorblade.easydblab.providers.ssm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The packaged `edl-ssm-proxy` wrapper, run for real through `/bin/sh` against a stub `aws`.
 *
 * The stub behaves like the AWS CLI in the failure QA found: it starts a "plugin" child that
 * ignores SIGTERM and SIGHUP and never reads its stdin, as a session-manager-plugin with a stuck
 * WebSocket does, and it never passes a termination on to that child. Without the wrapper both
 * outlive ssh, re-parented to PID 1.
 */
internal class SsmProxyWrapperScriptTest {
    @TempDir
    lateinit var dir: File

    private val started = mutableListOf<Process>()

    private val wrapper: File by lazy {
        File(dir, "edl-ssm-proxy").apply {
            writeText(requireNotNull(SsmProxyWrapper::class.java.getResource(SsmProxyWrapper.RESOURCE)).readText())
            setExecutable(true)
        }
    }

    private val stuckAws: File by lazy {
        File(dir, "aws").apply {
            writeText(
                """
                |#!/bin/sh
                |sh -c 'trap "" TERM HUP; echo ${'$'}${'$'} > "${'$'}0/plugin.pid"; exec sleep 300' "${dir.absolutePath}" &
                |echo ${'$'}${'$'} > "${dir.absolutePath}/aws.pid"
                |trap '' HUP
                |wait
                |
                """.trimMargin(),
            )
            setExecutable(true)
        }
    }

    @AfterEach
    fun cleanUp() {
        listOf("aws.pid", "plugin.pid").mapNotNull { pid(it) }.forEach { ProcessHandle.of(it).ifPresent { h -> h.destroyForcibly() } }
        started.forEach { it.destroyForcibly() }
    }

    @Test
    fun `data passes through to the command and back`() {
        val echo = File(dir, "echo-aws").apply { writeText("#!/bin/sh\nexec cat\n").also { setExecutable(true) } }
        val proxy = start(listOf(wrapper.absolutePath, echo.absolutePath))

        proxy.outputStream.apply {
            write("SSH-2.0-test\n".toByteArray())
            flush()
        }
        val line = proxy.inputStream.bufferedReader().readLine()
        proxy.outputStream.close()

        assertThat(line).isEqualTo("SSH-2.0-test")
        assertThat(proxy.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        assertThat(proxy.exitValue()).isZero()
    }

    /** ssh closing the connection is our stdin reaching EOF; the stuck plugin must not outlive it. */
    @Test
    fun `the command's process tree ends when stdin closes`() {
        val proxy = start(listOf(wrapper.absolutePath, stuckAws.absolutePath))
        awaitPids()

        proxy.outputStream.close()

        assertThat(proxy.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        awaitGone("plugin.pid")
        assertThat(alive("aws.pid")).isFalse()
        assertThat(alive("plugin.pid")).isFalse()
    }

    /** ssh killed outright sends no signal; the wrapper notices it was re-parented. */
    @Test
    fun `the command's process tree ends when the parent is killed`() {
        // A stand-in for ssh: it starts the wrapper with its own stdin, which this test holds open,
        // so only the parent's death, not an EOF, can end the tree.
        val parent =
            start(
                listOf(
                    "/bin/sh",
                    "-c",
                    "exec 3<&0; \"$1\" \"$2\" <&3 3<&- & echo \$! > \"$3/wrapper.pid\"; wait",
                    "fake-ssh",
                    wrapper.absolutePath,
                    stuckAws.absolutePath,
                    dir.absolutePath,
                ),
            )
        awaitPids()

        parent.destroyForcibly().waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)

        awaitGone("plugin.pid")
        assertThat(alive("aws.pid")).isFalse()
        assertThat(alive("plugin.pid")).isFalse()
        awaitGone("wrapper.pid")
    }

    @Test
    fun `the command's process tree ends when the wrapper gets SIGHUP from ssh`() {
        val proxy = start(listOf(wrapper.absolutePath, stuckAws.absolutePath))
        awaitPids()

        ProcessBuilder("kill", "-HUP", proxy.pid().toString()).start().waitFor()

        assertThat(proxy.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        awaitGone("plugin.pid")
        assertThat(alive("aws.pid")).isFalse()
        assertThat(alive("plugin.pid")).isFalse()
    }

    private fun start(command: List<String>): Process =
        ProcessBuilder(command)
            .apply { environment()["EDL_SSM_PROXY_GRACE_SECONDS"] = "1" }
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .also { started.add(it) }

    private fun pid(name: String): Long? =
        File(dir, name)
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.toLongOrNull()

    private fun alive(name: String): Boolean = pid(name)?.let { ProcessHandle.of(it).map { h -> h.isAlive }.orElse(false) } ?: false

    private fun awaitPids() = waitUntil { pid("aws.pid") != null && pid("plugin.pid") != null }

    /**
     * The wrapper SIGKILLs the plugin and exits at once, but the plugin was re-parented when the stub
     * `aws` died, and it stays a zombie until its new parent reaps it. [ProcessHandle.isAlive] counts
     * a zombie as alive, so a check made right after the wrapper exits can see it.
     */
    private fun awaitGone(name: String) = waitUntil { !alive(name) }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
    }

    private companion object {
        const val LIMIT_SECONDS = 20L
        const val POLL_MS = 50L
    }
}
