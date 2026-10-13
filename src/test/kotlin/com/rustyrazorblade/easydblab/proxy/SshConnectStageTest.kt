package com.rustyrazorblade.easydblab.proxy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tests for [SshConnectStage]: how far one `ssh -v` run got toward its server, read from synthetic
 * transcripts, and the sentence that names a connection that never came up.
 */
class SshConnectStageTest {
    @Test
    fun `a dial with no established connection is still connecting, to the address ssh printed`() {
        val transcript =
            listOf(
                "2026-10-13T03:44:04Z edl-socks-tunnel: starting ssh on port 20288",
                "debug1: Reading configuration data /ws/sshConfig",
                "debug1: Connecting to 54.1.2.3 [54.1.2.3] port 22.",
            )

        assertThat(SshConnectStage.of(transcript)).isEqualTo(SshConnectStage.Connecting("54.1.2.3", 22))
    }

    @Test
    fun `an established connection is past the connect stage`() {
        val transcript =
            listOf(
                "debug1: Connecting to 54.1.2.3 [54.1.2.3] port 22.",
                "debug1: Connection established.",
                "debug1: Authenticating to 54.1.2.3:22 as 'ubuntu'",
            )

        assertThat(SshConnectStage.of(transcript)).isEqualTo(SshConnectStage.Connected)
    }

    @Test
    fun `a second dial after an established connection is connecting again`() {
        val transcript =
            listOf(
                "debug1: Connecting to 54.1.2.3 [54.1.2.3] port 22.",
                "debug1: Connection established.",
                "debug1: Connecting to 54.9.9.9 [54.9.9.9] port 2222.",
            )

        assertThat(SshConnectStage.of(transcript)).isEqualTo(SshConnectStage.Connecting("54.9.9.9", 2222))
    }

    @Test
    fun `a proxy command with no server version is still connecting through it`() {
        val transcript =
            listOf(
                "debug1: Executing proxy command: exec aws ssm start-session --target i-0abc",
                "debug1: Local version string SSH-2.0-OpenSSH_9.9",
            )

        assertThat(SshConnectStage.of(transcript)).isEqualTo(SshConnectStage.ConnectingThroughProxyCommand)
    }

    @Test
    fun `a server version through a proxy command is past the connect stage`() {
        val transcript =
            listOf(
                "debug1: Executing proxy command: exec aws ssm start-session --target i-0abc",
                "debug1: Remote protocol version 2.0, remote software version OpenSSH_9.6p1",
            )

        assertThat(SshConnectStage.of(transcript)).isEqualTo(SshConnectStage.Connected)
    }

    @Test
    fun `a transcript with no dial has not started`() {
        val transcript = listOf("2026-10-13T03:44:04Z edl-socks-tunnel: starting ssh on port 20288")

        assertThat(SshConnectStage.of(transcript)).isEqualTo(SshConnectStage.NotStarted)
    }

    @Test
    fun `a dial names the host, the address, the port and the wait in whole seconds`() {
        val sentence = SshConnectStage.Connecting("54.1.2.3", 22).failureSentence("control0", Duration.ofMillis(4500))

        assertThat(sentence).isEqualTo("ssh could not connect to control0 (54.1.2.3) port 22 within 5s.")
    }

    @Test
    fun `a wait the wall clock stepped back on is reported as no wait, never a negative one`() {
        val sentence = SshConnectStage.Connecting("54.1.2.3", 22).failureSentence("control0", Duration.ofMillis(-2500))

        assertThat(sentence).isEqualTo("ssh could not connect to control0 (54.1.2.3) port 22 within 0s.")
    }

    @Test
    fun `a proxy command names the host and the wait`() {
        val sentence = SshConnectStage.ConnectingThroughProxyCommand.failureSentence("control0", Duration.ofSeconds(30))

        assertThat(sentence).isEqualTo("ssh could not connect to control0 through its ProxyCommand within 30s.")
    }

    @Test
    fun `a stage past or before the connect makes no connect claim`() {
        assertThat(SshConnectStage.Connected.failureSentence("control0", Duration.ofSeconds(5))).isNull()
        assertThat(SshConnectStage.NotStarted.failureSentence("control0", Duration.ofSeconds(5))).isNull()
    }
}
