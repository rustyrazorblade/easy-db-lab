package com.rustyrazorblade.easydblab.services

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/** Outcome of one short-lived local CLI invocation. */
sealed interface LocalCliResult {
    /**
     * The CLI ran to completion.
     *
     * @property stderr kept apart from [stdout], so a warning on stderr cannot corrupt output a
     *   caller parses, such as `tailscale status --json`
     */
    data class Completed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String = "",
    ) : LocalCliResult

    /** The executable is not on this machine's PATH. */
    data object BinaryNotFound : LocalCliResult

    /** The CLI did not exit within the timeout and was killed. */
    data object TimedOut : LocalCliResult
}

/**
 * Seam for running a CLI on the operator's machine, such as `tailscale` or the AWS tooling.
 *
 * Preflight checks use it to tell a missing binary from a present-but-unhappy one. It exists so
 * their classification can be driven in tests without depending on what the developer's own
 * machine has installed or logged in. Production wires in [DefaultLocalCliRunner].
 */
fun interface LocalCliRunner {
    /**
     * @param command the full command line to run.
     * @param timeout how long to wait before killing the process.
     */
    fun run(
        command: List<String>,
        timeout: Duration,
    ): LocalCliResult
}

/**
 * Production [LocalCliRunner]: spawns the real executable via [ProcessBuilder].
 *
 * [ProcessBuilder.start] throws [IOException] when the executable is absent from PATH, which is
 * how a missing tool is told apart from one that runs and fails.
 */
object DefaultLocalCliRunner : LocalCliRunner {
    override fun run(
        command: List<String>,
        timeout: Duration,
    ): LocalCliResult =
        try {
            val process = ProcessBuilder(command).start()
            process.outputStream.close()
            // Waiting before reading is safe only because callers run commands whose output is
            // bounded and small, so the process can never block on a full stdout pipe.
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                LocalCliResult.TimedOut
            } else {
                LocalCliResult.Completed(
                    process.exitValue(),
                    process.inputStream.bufferedReader().readText(),
                    process.errorStream.bufferedReader().readText(),
                )
            }
        } catch (e: IOException) {
            log.debug(e) { "Could not run ${command.joinToString(" ")}" }
            LocalCliResult.BinaryNotFound
        }
}
