package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The packaged `edl-socks-tunnel` script, run for real under `/bin/sh` (and dash, where it is
 * installed) against a stub `ssh` that records each run and then exits or stays up.
 *
 * Each run of the stub appends `<pid> <epoch seconds> <arguments>` to `runs.log`. Its first
 * `STUB_FAILS` runs exit at once with status 255, as an ssh whose connection died does; later runs
 * stay up, as a connected ssh does.
 */
internal class TunnelScriptTest {
    @TempDir
    lateinit var root: File

    private lateinit var script: File
    private lateinit var stubBin: File
    private val runsLog: File get() = File(root, "runs.log")
    private val started = mutableListOf<Process>()

    @BeforeEach
    fun setUp() {
        script =
            File(root, Constants.ToolWrappers.TUNNEL_SCRIPT).apply {
                writeText(requireNotNull(TunnelScriptTest::class.java.getResource(Constants.ToolWrappers.TUNNEL_RESOURCE)).readText())
                setExecutable(true)
            }
        stubBin = File(root, "stub").apply { mkdirs() }
        File(stubBin, "ssh").apply {
            writeText(
                """
                |#!/bin/sh
                |echo "${'$'}${'$'} ${'$'}(date +%s) ${'$'}*" >> "${runsLog.absolutePath}"
                |runs=${'$'}(wc -l < "${runsLog.absolutePath}")
                |if [ "${'$'}runs" -le "${'$'}{STUB_FAILS:-0}" ]; then exit 255; fi
                |exec sleep 300
                |
                """.trimMargin(),
            )
            setExecutable(true)
        }
    }

    @AfterEach
    fun endEverythingStarted() {
        started.forEach { process ->
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    @ParameterizedTest(name = "under {0}")
    @MethodSource("shells")
    fun `an ssh that exits is started again on the same port after the backoff`(shell: String) {
        launch(shell, failures = 2, backoffSeconds = 1, graceSeconds = 0)

        val runs = awaitRuns(3)

        assertThat(runs.map { it.arguments }).containsOnly(SSH_ARGUMENTS)
        runs.zipWithNext().forEach { (before, after) ->
            assertThat(
                after.epochSeconds - before.epochSeconds,
            ).withFailMessage("restarted before the backoff: $runs").isGreaterThanOrEqualTo(1)
        }
    }

    @ParameterizedTest(name = "under {0}")
    @MethodSource("shells")
    fun `TERM ends the running ssh and the loop, and nothing is started afterwards`(shell: String) {
        val tunnel = launch(shell, failures = 0, backoffSeconds = 1, graceSeconds = 0)
        val ssh = ProcessHandle.of(awaitRuns(1).single().pid).orElseThrow()

        tunnel.destroy()

        assertThat(tunnel.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).withFailMessage("the script did not exit on TERM").isTrue()
        assertThat(ssh.onExit().get(LIMIT_SECONDS, TimeUnit.SECONDS).isAlive).isFalse()
        Thread.sleep(TimeUnit.SECONDS.toMillis(2))
        assertThat(runsLog.readLines()).hasSize(1)
    }

    @ParameterizedTest(name = "under {0}")
    @MethodSource("shells")
    fun `TERM during the backoff ends the loop without starting ssh again`(shell: String) {
        val tunnel = launch(shell, failures = 1, backoffSeconds = 3, graceSeconds = 0)
        awaitRuns(1)

        tunnel.destroy()

        assertThat(tunnel.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).withFailMessage("the script did not exit on TERM").isTrue()
        Thread.sleep(TimeUnit.SECONDS.toMillis(4))
        assertThat(runsLog.readLines()).hasSize(1)
        assertThat(tunnel.descendants().filter { it.isAlive }.count()).isZero()
    }

    @ParameterizedTest(name = "under {0}")
    @MethodSource("shells")
    fun `a first ssh that fails within the start-up grace ends the script with its status`(shell: String) {
        val tunnel = launch(shell, failures = 1, backoffSeconds = 1, graceSeconds = 60)

        assertThat(tunnel.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).withFailMessage("the script kept restarting a failed start").isTrue()
        assertThat(tunnel.exitValue()).isEqualTo(SSH_FAILURE)
        assertThat(runsLog.readLines()).hasSize(1)
    }

    private fun launch(
        shell: String,
        failures: Int,
        backoffSeconds: Int,
        graceSeconds: Int,
    ): Process =
        ProcessBuilder(shell, script.absolutePath, "$PORT", SSH_CONFIG, "$backoffSeconds", "$graceSeconds", HOST)
            .redirectErrorStream(true)
            .redirectOutput(File(root, "tunnel.log"))
            .apply {
                environment()["PATH"] = "${stubBin.absolutePath}:/usr/bin:/bin"
                environment()["STUB_FAILS"] = "$failures"
            }.start()
            .also { started += it }

    /** One run of the stub ssh. */
    private data class Run(
        val pid: Long,
        val epochSeconds: Long,
        val arguments: String,
    )

    /** Waits until the stub ssh has run [count] times, and returns those runs. */
    private fun awaitRuns(count: Int): List<Run> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS)
        while (!runsLog.exists() || runsLog.readLines().size < count) {
            check(System.nanoTime() < deadline) { "ssh ran ${runsLog.takeIf { it.exists() }?.readLines()?.size ?: 0} times, not $count" }
            Thread.sleep(POLL_MS)
        }
        return runsLog.readLines().take(count).map { line ->
            val (pid, epoch, arguments) = line.split(' ', limit = 3)
            Run(pid.toLong(), epoch.toLong(), arguments)
        }
    }

    private companion object {
        const val PORT = 41234
        const val SSH_CONFIG = "/work/sshConfig"
        const val HOST = "control0"
        const val SSH_FAILURE = 255
        const val LIMIT_SECONDS = 15L
        const val POLL_MS = 50L
        const val SSH_ARGUMENTS = "-v -o ExitOnForwardFailure=yes -N -D $PORT -F $SSH_CONFIG $HOST"

        /** `/bin/sh`, and dash too where it is installed (it is `/bin/sh` on Debian and Ubuntu). */
        @JvmStatic
        fun shells(): List<String> = listOf("/bin/sh", "/bin/dash").filter { File(it).canExecute() }
    }
}
