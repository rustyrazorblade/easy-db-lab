package com.rustyrazorblade.easydblab.configuration.compactor

import com.github.dockerjava.api.DockerClient
import java.time.Duration

/** How long Docker may take to mark an exec whose output stream has closed as finished. */
private val EXEC_SETTLE: Duration = Duration.ofSeconds(30)
private val EXEC_SETTLE_POLL: Duration = Duration.ofMillis(20)

/**
 * Runs [command] in [container] and returns its exit code.
 *
 * The exec's output stream can close before Docker marks the exec finished; under concurrent execs
 * an inspect then still reports it running, with no exit code. So it inspects until Docker reports
 * the exec finished, and fails when it does not within [EXEC_SETTLE].
 */
internal fun execExitCode(
    docker: DockerClient,
    container: String,
    command: List<String>,
): Long {
    val exec =
        docker
            .execCreateCmd(container)
            .withCmd(*command.toTypedArray())
            .exec()
    docker.execStartCmd(exec.id).start().awaitCompletion()
    val deadline = System.nanoTime() + EXEC_SETTLE.toNanos()
    while (true) {
        val inspect = docker.inspectExecCmd(exec.id).exec()
        val exitCode = inspect.exitCodeLong
        if (inspect.isRunning != true && exitCode != null) return exitCode
        check(System.nanoTime() < deadline) { "exec $command in $container did not finish within $EXEC_SETTLE" }
        Thread.sleep(EXEC_SETTLE_POLL.toMillis())
    }
}
