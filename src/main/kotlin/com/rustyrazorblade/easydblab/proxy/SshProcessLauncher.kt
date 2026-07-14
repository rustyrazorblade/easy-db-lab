package com.rustyrazorblade.easydblab.proxy

import java.io.File

/**
 * Launches the detached `ssh -N -D` OS process that backs a SOCKS5 proxy.
 *
 * This is the injectable seam [ProcessSocksProxyService] uses to spawn ssh. It exists so the
 * service's start / verify / cleanup decisions — which command line is built, how a dead or hanging
 * ssh is surfaced, and that a process is destroyed on verification failure — can be driven in tests
 * with a fake [Process], without spawning a real `ssh` against a live host. A real ssh to a fixed
 * loopback port is non-deterministic across host network stacks (an instant RST on macOS loopback
 * vs. a silently dropped SYN on a CI runner — issue #750); a fake process removes that timing
 * dependence entirely. Production wires in [DefaultSshProcessLauncher].
 */
fun interface SshProcessLauncher {
    /**
     * @param command the full process command line (`nohup ssh -v … <alias>`).
     * @param logFile file the ssh `-v` transcript (stderr) is redirected to.
     * @return the started [Process].
     */
    fun launch(
        command: List<String>,
        logFile: File,
    ): Process
}

/**
 * Production [SshProcessLauncher]: spawns the real `ssh` via [ProcessBuilder], detaching stdin and
 * stdout to `/dev/null` and redirecting stderr (the `ssh -v` transcript) to the log file so a failed
 * start's real error survives for diagnosis. The redirect overwrites, so the log is always exactly
 * this attempt's transcript.
 */
object DefaultSshProcessLauncher : SshProcessLauncher {
    override fun launch(
        command: List<String>,
        logFile: File,
    ): Process =
        ProcessBuilder(command)
            .apply {
                redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                redirectError(ProcessBuilder.Redirect.to(logFile))
            }.start()
}
