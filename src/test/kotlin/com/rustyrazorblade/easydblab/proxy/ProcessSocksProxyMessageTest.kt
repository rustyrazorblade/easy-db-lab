package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Context
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for [ProcessSocksProxyService.verificationFailureMessage] — the failure message built
 * when the SOCKS5 proxy never comes up.
 *
 * The message must surface the REAL, un-prefixed ssh error line from the `ssh -v` transcript (e.g.
 * "Connection refused") while dropping the `debug1:`/`debug2:`/`debug3:` chatter that `ssh -v`
 * emits. This was previously proven by spawning a real `ssh -v` at a refused loopback port and
 * scraping its live transcript, which was non-deterministic across host network stacks (an instant
 * RST on macOS loopback vs. a silently dropped SYN on the CI runner — issue #750). Feeding a
 * synthetic transcript to the pure message builder proves the same contract with no ssh process, no
 * socket, and no timing dependence, so it lives in the fast unit tier.
 */
class ProcessSocksProxyMessageTest {
    @TempDir
    lateinit var tempDir: File

    /** Never invoked here — [ProcessSocksProxyService.verificationFailureMessage] does no probing. */
    private val unusedProbe = TunnelReachabilityProbe { _, _, _ -> false }

    private fun service() = ProcessSocksProxyService(Context.forCli(tempDir), unusedProbe)

    private fun writeTranscript(vararg lines: String): File =
        File(tempDir, "socks5-proxy.log").apply { writeText(lines.joinToString("\n")) }

    @Test
    fun `surfaces the real ssh error and omits debug noise`() {
        val logFile =
            writeTranscript(
                "OpenSSH_9.9p2, LibreSSL 3.3.6",
                "debug1: Reading configuration data sshConfig",
                "debug1: Connecting to 127.0.0.1 [127.0.0.1] port 1.",
                "debug2: fd 3 setting O_NONBLOCK",
                "debug3: send packet: type 21",
                "ssh: connect to host 127.0.0.1 port 1: Connection refused",
            )

        val message = service().verificationFailureMessage(logFile, exitCode = 255)

        // The real, un-prefixed ssh error must be pulled from the transcript into the message...
        assertThat(message)
            .contains("Connection refused")
            .contains("ssh exited with code 255")
            // ...and the debug chatter must NOT be.
            .doesNotContain("debug1:")
            .doesNotContain("debug2:")
            .doesNotContain("debug3:")
    }

    @Test
    fun `omits the exit-code clause when ssh was still alive (timed out)`() {
        val logFile =
            writeTranscript(
                "debug1: Authentication succeeded (publickey).",
                "channel 0: open failed: administratively prohibited: open failed",
            )

        // A null exit code models the timeout path: the tunnel never became reachable but ssh had
        // not died, so no "ssh exited with code N" clause should be present.
        val message = service().verificationFailureMessage(logFile, exitCode = null)

        assertThat(message)
            .contains("channel 0: open failed")
            .doesNotContain("ssh exited with code")
            .doesNotContain("debug1:")
    }
}
