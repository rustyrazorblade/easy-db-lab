package com.rustyrazorblade.easydblab.ssh

import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.output.NodeStream
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.Transferable
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.Collections

/**
 * [SSHClient] against a real `sshd`: what a remote command prints reaches the sink one whole line
 * at a time, on the stream it was written to, while the command is still running, and with a
 * multi-byte character split across reads kept whole. Only a real channel delivers output in the
 * chunks the network gives it.
 */
internal class SSHClientStreamingIntegrationTest {
    /** Every line the sink got, with its stream and the time it arrived. */
    private data class Received(
        val stream: NodeStream,
        val text: String,
        val atNanos: Long,
    )

    private val received: MutableList<Received> = Collections.synchronizedList(mutableListOf())
    private val sink = RemoteOutputSink { stream, text -> received += Received(stream, text, System.nanoTime()) }

    private fun lines(stream: NodeStream) = synchronized(received) { received.filter { it.stream == stream }.map { it.text } }

    @Test
    fun `interleaved stdout and stderr arrive as whole lines, each on its own stream`() {
        val script =
            "for i in \$(seq 1 300); do echo \"out \$i ünïcödé\"; echo \"err \$i\" >&2; done; printf 'no newline at the end'"

        client().executeRemoteCommand(script, sink, secret = false)

        assertThat(lines(NodeStream.Stdout)).containsExactlyElementsOf((1..300).map { "out $it ünïcödé" } + "no newline at the end")
        assertThat(lines(NodeStream.Stderr)).containsExactlyElementsOf((1..300).map { "err $it" })
    }

    @Test
    fun `a line is delivered while the command is still running`() {
        val started = System.nanoTime()

        client().executeRemoteCommand("echo first; sleep $SLOW_SECONDS; echo second", sink, secret = false)

        val finished = System.nanoTime()
        val first = synchronized(received) { received.single { it.text == "first" } }
        assertThat(Duration.ofNanos(finished - first.atNanos)).isGreaterThanOrEqualTo(Duration.ofMillis(SLOW_MARGIN_MS))
        assertThat(Duration.ofNanos(first.atNanos - started)).isLessThan(Duration.ofSeconds(SLOW_SECONDS))
        assertThat(lines(NodeStream.Stdout)).containsExactly("first", "second")
    }

    @Test
    fun `a failed command streams what it printed before the failure`() {
        assertThatThrownBy {
            client().executeRemoteCommand("echo working; echo 'ERROR: broken' >&2; exit 3", sink, secret = false)
        }.isInstanceOf(RemoteCommandFailedException::class.java)

        assertThat(lines(NodeStream.Stdout)).containsExactly("working")
        assertThat(lines(NodeStream.Stderr)).containsExactly("ERROR: broken")
    }

    @Test
    fun `a secret command streams nothing`() {
        val response = client().executeRemoteCommand("echo token-value", sink, secret = true)

        assertThat(received).isEmpty()
        assertThat(response.text).contains("token-value")
    }

    @Test
    fun `credentials in a URL the command prints are redacted in the streamed line`() {
        client().executeRemoteCommand("echo 'cloning https://user:hunter2@example.com/repo.git'", sink, secret = false)

        assertThat(lines(NodeStream.Stdout).single()).doesNotContain("hunter2").contains("example.com/repo.git")
    }

    private fun client(): SSHClient = SSHClient(session)

    companion object {
        private const val SSHD_PORT = 22
        private const val SLOW_SECONDS = 2L
        private const val SLOW_MARGIN_MS = 1500L
        private const val CONNECT_SECONDS = 30L

        /** `0600`: sshd refuses an `authorized_keys` others can write. */
        private const val AUTHORIZED_KEYS_MODE = 384

        /** An Alpine `sshd` that takes the test's key for root, pinned to the alpine:3.20 image index. */
        private val SSHD_DOCKERFILE =
            """
            FROM alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc
            RUN apk add --no-cache openssh-server \
             && ssh-keygen -A \
             && mkdir -p /root/.ssh \
             && chmod 700 /root/.ssh
            CMD ["/usr/sbin/sshd", "-D", "-e", "-o", "PermitRootLogin=prohibit-password", "-o", "PasswordAuthentication=no"]
            """.trimIndent()

        private lateinit var keyDirectory: File
        private lateinit var sshd: GenericContainer<*>
        private lateinit var mina: SshClient
        private lateinit var session: ClientSession

        @BeforeAll
        @JvmStatic
        fun connect() {
            keyDirectory = Files.createTempDirectory("edl-sshd-key").toFile()
            val privateKey = File(keyDirectory, "id_ed25519")
            val keygen = ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", privateKey.absolutePath).inheritIO().start()
            check(keygen.waitFor() == 0) { "ssh-keygen failed" }
            sshd =
                GenericContainer(ImageFromDockerfile("edl-test-sshd-plain", false).withFileFromString("Dockerfile", SSHD_DOCKERFILE))
                    .withExposedPorts(SSHD_PORT)
                    .withCopyToContainer(
                        Transferable.of(File(keyDirectory, "id_ed25519.pub").readText(), AUTHORIZED_KEYS_MODE),
                        "/root/.ssh/authorized_keys",
                    ).waitingFor(Wait.forListeningPort())
            sshd.start()

            val keyPairs = SecurityUtils.getKeyPairResourceParser().loadKeyPairs(null, privateKey.toPath(), null).toList()
            mina = SshClient.setUpDefaultClient().apply { keyIdentityProvider = KeyIdentityProvider.wrapKeyPairs(keyPairs) }
            mina.start()
            session =
                mina
                    .connect("root", sshd.host, sshd.getMappedPort(SSHD_PORT))
                    .verify(Duration.ofSeconds(CONNECT_SECONDS))
                    .session
            session.auth().verify(Duration.ofSeconds(CONNECT_SECONDS))
        }

        @AfterAll
        @JvmStatic
        fun disconnect() {
            session.close(true)
            mina.stop()
            sshd.stop()
            keyDirectory.deleteRecursively()
        }
    }
}
