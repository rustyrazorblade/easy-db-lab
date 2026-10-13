package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.DirectSshRoute
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

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

    private fun service(connectFailureHint: String? = null) =
        ProcessSocksProxyService(Context.forCli(tempDir), unusedProbe, EventBus(), connectFailureHint = { connectFailureHint })

    private val directHint = DirectSshRoute(sshPort = 22).tunnelConnectFailureHint(sshOpenToAll = false)

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

        val message = service().verificationFailureMessage(logFile, exitCode = 255, gatewayAlias = "control0")

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
        val message = service().verificationFailureMessage(logFile, exitCode = null, gatewayAlias = "control0")

        assertThat(message)
            .contains("channel 0: open failed")
            .doesNotContain("ssh exited with code")
            .doesNotContain("debug1:")
    }

    @Test
    fun `a live direct ssh that never connected names the host, address, port, wait and the IP rule`() {
        val logFile =
            writeTranscript(
                "2026-10-13T03:44:03Z easy-db-lab: launching the SOCKS5 tunnel on port 20288 to control0",
                "2026-10-13T03:44:04Z edl-socks-tunnel: starting ssh on port 20288",
                "debug1: Connecting to 54.1.2.3 [54.1.2.3] port 22.",
            )

        // Ten direct attempts, each a probe of up to a second and a 500ms pause, took 14.2s.
        val message =
            service(connectFailureHint = directHint)
                .verificationFailureMessage(logFile, exitCode = null, gatewayAlias = "control0", stalledFor = Duration.ofMillis(14_200))

        assertThat(message)
            .contains("ssh could not connect to control0 (54.1.2.3) port 22 within 15s.")
            .contains(
                "security group allows SSH only from the public IP recorded when `up` ran, so a changed public IP is the likely cause.",
            ).contains("See ${logFile.absolutePath}")
            .contains("ssh reported:\n2026-10-13T03:44:03Z easy-db-lab: launching")
            .doesNotContain("ssh exited with code")
            .doesNotContain("debug1:")
    }

    @Test
    fun `a live ssm ssh still connecting through its proxy command names the stage without the IP rule`() {
        val logFile =
            writeTranscript(
                "debug1: Executing proxy command: exec aws ssm start-session --target i-0abc",
                "debug1: Local version string SSH-2.0-OpenSSH_9.9",
            )

        val message =
            service().verificationFailureMessage(logFile, exitCode = null, gatewayAlias = "control0", stalledFor = Duration.ofSeconds(31))

        assertThat(message)
            .contains("ssh could not connect to control0 through its ProxyCommand within 31s.")
            .doesNotContain("security group")
    }

    @Test
    fun `an ssh that died with a real error keeps the error and makes no connect claim`() {
        val logFile =
            writeTranscript(
                "debug1: Connecting to 54.1.2.3 [54.1.2.3] port 22.",
                "ssh: connect to host 54.1.2.3 port 22: Operation timed out",
            )

        val message =
            service(
                connectFailureHint = directHint,
            ).verificationFailureMessage(logFile, exitCode = 255, gatewayAlias = "control0")

        assertThat(message)
            .contains("ssh exited with code 255")
            .contains("ssh reported:\nssh: connect to host 54.1.2.3 port 22: Operation timed out")
            .doesNotContain("could not connect to control0")
            .doesNotContain("security group")
    }

    @Test
    fun `a live ssh past the connection makes no connect claim`() {
        val logFile =
            writeTranscript(
                "debug1: Connecting to 54.1.2.3 [54.1.2.3] port 22.",
                "debug1: Connection established.",
                "debug1: Authenticating to 54.1.2.3:22 as 'ubuntu'",
            )

        val message =
            service(connectFailureHint = directHint)
                .verificationFailureMessage(logFile, exitCode = null, gatewayAlias = "control0", stalledFor = Duration.ofSeconds(15))

        assertThat(message)
            .startsWith("SOCKS5 proxy failed to establish a working tunnel. See ${logFile.absolutePath}")
            .doesNotContain("could not connect")
            .doesNotContain("security group")
    }
}
