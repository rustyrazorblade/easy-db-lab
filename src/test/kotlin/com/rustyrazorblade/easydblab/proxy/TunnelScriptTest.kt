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
 * installed) from a workspace's `bin/`, against a stub `ssh` that records each run and then exits or
 * stays up.
 *
 * Each run of the stub appends `<pid> <epoch seconds> <arguments>` to `runs.log`. Its first
 * `STUB_FAILS` runs exit at once with status `STUB_STATUS` (255 unless set), as an ssh whose
 * connection died does; the run numbered `STUB_BIND_FAILS_ON` first prints what ssh prints when its
 * `-D` port is taken. Later runs stay up, as a connected ssh does.
 */
internal class TunnelScriptTest {
    @TempDir
    lateinit var root: File

    private lateinit var workspace: File
    private lateinit var script: File
    private lateinit var stubBin: File
    private val runsLog: File get() = File(root, "runs.log")
    private val tunnelLog: File get() = File(root, "socks5-proxy.log")
    private val started = mutableListOf<Process>()

    @BeforeEach
    fun setUp() {
        workspace = File(root, "ws")
        val bin = File(workspace, Constants.ToolWrappers.DIRECTORY).apply { mkdirs() }
        script =
            File(bin, Constants.ToolWrappers.TUNNEL_SCRIPT).apply {
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
                |if [ "${'$'}runs" -eq "${'$'}{STUB_BIND_FAILS_ON:-0}" ]; then
                |  echo "bind [127.0.0.1]:$PORT: Address already in use" >&2
                |  echo "channel_setup_fwd_listener_tcpip: cannot listen to port: $PORT" >&2
                |  exit 255
                |fi
                |if [ "${'$'}runs" -le "${'$'}{STUB_FAILS:-0}" ]; then exit "${'$'}{STUB_STATUS:-255}"; fi
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
    fun `a verified tunnel whose ssh exits right after the start is started again on the same port after the backoff`(shell: String) {
        recordPort(PORT)
        launch(shell, failures = 2)

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
    fun `an unverified tunnel whose ssh exits ends the script with ssh's status`(shell: String) {
        // A status other than ssh's usual 255, so the test sees the script pass on ssh's own status.
        val tunnel = launch(shell, failures = 1, status = DISTINCT_STATUS)

        assertThat(tunnel.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).withFailMessage("the script restarted an unverified tunnel").isTrue()
        assertThat(tunnel.exitValue()).isEqualTo(DISTINCT_STATUS)
        assertThat(runsLog.readLines()).hasSize(1)
    }

    @ParameterizedTest(name = "under {0}")
    @MethodSource("shells")
    fun `an env file that records another port does not count as verified`(shell: String) {
        recordPort(PORT + 1)
        val tunnel = launch(shell, failures = 1, status = DISTINCT_STATUS)

        assertThat(tunnel.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).withFailMessage("the script restarted an unverified tunnel").isTrue()
        assertThat(tunnel.exitValue()).isEqualTo(DISTINCT_STATUS)
        assertThat(runsLog.readLines()).hasSize(1)
    }

    @ParameterizedTest(name = "under {0}")
    @MethodSource("shells")
    fun `a restarted ssh that cannot bind its port ends the script after that one attempt`(shell: String) {
        recordPort(PORT)
        val tunnel = launch(shell, failures = 1, bindFailsOn = 2)

        assertThat(tunnel.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).withFailMessage("the script kept restarting a taken port").isTrue()
        assertThat(tunnel.exitValue()).isEqualTo(SSH_FAILURE)
        Thread.sleep(PAST_BACKOFF_MS)
        assertThat(runsLog.readLines()).hasSize(2)
    }

    @ParameterizedTest(name = "under {0}")
    @MethodSource("shells")
    fun `TERM ends the running ssh and the loop, and nothing is started afterwards`(shell: String) {
        recordPort(PORT)
        val tunnel = launch(shell, failures = 0)
        val ssh = ProcessHandle.of(awaitRuns(1).single().pid).orElseThrow()

        tunnel.destroy()

        assertThat(tunnel.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).withFailMessage("the script did not exit on TERM").isTrue()
        assertThat(ssh.onExit().get(LIMIT_SECONDS, TimeUnit.SECONDS).isAlive).isFalse()
        Thread.sleep(PAST_BACKOFF_MS)
        assertThat(runsLog.readLines()).hasSize(1)
    }

    @ParameterizedTest(name = "under {0}")
    @MethodSource("shells")
    fun `TERM during the backoff ends the backoff and the loop without starting ssh again`(shell: String) {
        recordPort(PORT)
        val tunnel = launch(shell, failures = 1)
        awaitRuns(1)
        val backoff = awaitDescendant(tunnel, "sleep")

        tunnel.destroy()

        assertThat(tunnel.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).withFailMessage("the script did not exit on TERM").isTrue()
        assertThat(backoff.onExit().get(LIMIT_SECONDS, TimeUnit.SECONDS).isAlive).withFailMessage("the backoff sleep outlived TERM").isFalse()
        Thread.sleep(PAST_BACKOFF_MS)
        assertThat(runsLog.readLines()).hasSize(1)
    }

    @ParameterizedTest(name = "under {0}")
    @MethodSource("shells")
    fun `INT ends neither the script nor its ssh`(shell: String) {
        recordPort(PORT)
        val tunnel = launch(shell, failures = 0)
        val ssh = ProcessHandle.of(awaitRuns(1).single().pid).orElseThrow()

        assertThat(ProcessBuilder("kill", "-INT", "${tunnel.pid()}").start().waitFor()).isZero()
        Thread.sleep(PAST_BACKOFF_MS)

        assertThat(tunnel.isAlive).withFailMessage("INT ended the script").isTrue()
        assertThat(ssh.isAlive).withFailMessage("INT ended ssh").isTrue()
        assertThat(runsLog.readLines()).hasSize(1)
    }

    @ParameterizedTest(name = "under {0}")
    @MethodSource("shells")
    fun `the script appends to the log, timestamps every line, and logs each ssh start`(shell: String) {
        tunnelLog.writeText("an earlier launch\n")
        recordPort(PORT)
        val tunnel = launch(shell, failures = 1)
        awaitRuns(2)
        tunnel.destroy()
        assertThat(tunnel.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue()

        val lines = tunnelLog.readLines()

        assertThat(lines.first()).isEqualTo("an earlier launch")
        assertThat(lines.drop(1)).isNotEmpty.allMatch { TIMESTAMPED.matches(it) }
        assertThat(lines.filter { it.endsWith("edl-socks-tunnel: starting ssh on port $PORT") }).hasSize(2)
    }

    private fun recordPort(port: Int) {
        File(workspace, Constants.Proxy.ENV_FILE).writeText("${Constants.Proxy.ENV_TAILSCALE_ACTIVE}=false\n${Constants.Proxy.ENV_SOCKS_PORT}=$port\n")
    }

    private fun launch(
        shell: String,
        failures: Int,
        status: Int = SSH_FAILURE,
        bindFailsOn: Int = 0,
    ): Process =
        ProcessBuilder(shell, script.absolutePath, "$PORT", SSH_CONFIG, "$BACKOFF_SECONDS", tunnelLog.absolutePath, HOST)
            .redirectErrorStream(true)
            .redirectOutput(File(root, "tunnel.out"))
            .apply {
                environment()["PATH"] = "${stubBin.absolutePath}:/usr/bin:/bin"
                environment()["STUB_FAILS"] = "$failures"
                environment()["STUB_STATUS"] = "$status"
                environment()["STUB_BIND_FAILS_ON"] = "$bindFailsOn"
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

    /** Waits until [process] has a live descendant whose executable is named [name], and returns it. */
    private fun awaitDescendant(
        process: Process,
        name: String,
    ): ProcessHandle {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS)
        while (true) {
            process
                .descendants()
                .filter { handle -> handle.info().command().map { File(it).name == name }.orElse(false) }
                .findFirst()
                .orElse(null)
                ?.let { return it }
            check(System.nanoTime() < deadline) { "no $name ran under the script" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val PORT = 41234
        const val SSH_CONFIG = "/work/sshConfig"
        const val HOST = "control0"
        const val SSH_FAILURE = 255
        const val DISTINCT_STATUS = 3
        const val BACKOFF_SECONDS = 1
        const val PAST_BACKOFF_MS = 1500L
        const val LIMIT_SECONDS = 15L
        const val POLL_MS = 50L
        const val SSH_ARGUMENTS = "-v -o ExitOnForwardFailure=yes -N -D $PORT -F $SSH_CONFIG $HOST"
        val TIMESTAMPED = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z edl-socks-tunnel: .+""")

        /** `/bin/sh`, and dash too where it is installed (it is `/bin/sh` on Debian and Ubuntu). */
        @JvmStatic
        fun shells(): List<String> = listOf("/bin/sh", "/bin/dash").filter { File(it).canExecute() }
    }
}
