package com.rustyrazorblade.easydblab.export.query

import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * What one `docker compose` command did.
 *
 * @property exitCode its exit status.
 * @property output its standard output and error, interleaved.
 */
data class ComposeResult(
    val exitCode: Int,
    val output: String,
)

/**
 * Runs the `docker compose` CLI for a query export's temporary projects, as the operator would run it
 * in the project's directory. The seam lets the export's own logic be tested without docker.
 */
fun interface ComposeCli {
    /** Runs `docker compose -p <project> <args>` in [directory], waiting up to [timeout]. */
    fun run(
        directory: File,
        project: String,
        args: List<String>,
        timeout: Duration,
    ): ComposeResult
}

/**
 * [ComposeCli] over the `docker compose` CLI. Its output is read as it comes, so a long run cannot
 * block on a full pipe; a run that outlasts its timeout is killed and reported as failed.
 */
object DockerComposeCli : ComposeCli {
    private const val KILLED = -1

    override fun run(
        directory: File,
        project: String,
        args: List<String>,
        timeout: Duration,
    ): ComposeResult {
        val process =
            ProcessBuilder(listOf("docker", "compose", "-p", project) + args)
                .directory(directory)
                .redirectErrorStream(true)
                .start()
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { synchronized(output) { output.appendLine(it) } } }
        reader.isDaemon = true
        reader.start()
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            reader.join(timeout.toMillis().coerceAtMost(KILL_WAIT_MILLIS))
            return ComposeResult(KILLED, "docker compose ${args.joinToString(" ")} did not finish within $timeout\n$output")
        }
        reader.join()
        return ComposeResult(process.exitValue(), synchronized(output) { output.toString() })
    }

    private const val KILL_WAIT_MILLIS = 5_000L
}
