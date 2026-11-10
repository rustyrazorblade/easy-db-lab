package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * [SshClusterTooling] against a mocked [RemoteOperationsService], which CLAUDE.md allows for remote
 * CLI tools, and a real [ClusterStateManager] over a `@TempDir` state file, so a change of cluster state
 * is a real one.
 */
internal class SshClusterToolingTest {
    @TempDir
    lateinit var stateDir: File

    @TempDir
    lateinit var tempDir: File

    private val remoteOps: RemoteOperationsService = mock()
    private lateinit var stateManager: ClusterStateManager
    private lateinit var tooling: SshClusterTooling

    private val controlHost = clusterHost("control0", "54.1.2.3", "10.0.0.1")
    private val kubeconfig = "KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}"

    private fun clusterHost(
        alias: String,
        publicIp: String,
        privateIp: String,
    ) = ClusterHost(publicIp = publicIp, privateIp = privateIp, alias = alias, availabilityZone = "us-west-2a")

    private fun saveState(vararg controls: ClusterHost) {
        stateManager.save(
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                hosts = mapOf(ServerType.Control to controls.toList()),
            ),
        )
    }

    @BeforeEach
    fun setup() {
        stateManager = ClusterStateManager(File(stateDir, "state.json"))
        saveState(controlHost)
        tooling = SshClusterTooling(remoteOps, stateManager, tempDir.toPath())
        whenever(remoteOps.executeRemotely(any(), any(), any(), any())).doReturn(Response("out"))
    }

    private fun remoteCommands(): List<String> {
        val commands = argumentCaptor<String>()
        verify(remoteOps, org.mockito.kotlin.atLeast(0)).executeRemotely(any(), commands.capture(), any(), any())
        return commands.allValues
    }

    private fun tempDirEntries(): List<String> = tempDir.list().orEmpty().toList()

    @Test
    fun `a command runs on the control node with the control node kubeconfig and returns stdout`() {
        val stdout = tooling.run(ClusterTool.KUBECTL, listOf("apply", "--server-side", "-f", "https://example.com/m.yaml"))

        assertThat(stdout).isEqualTo("out")
        verify(remoteOps).executeRemotely(
            controlHost.toHost(),
            "$kubeconfig kubectl apply --server-side -f https://example.com/m.yaml",
            true,
            false,
        )
    }

    @Test
    fun `echo false asks the remote service not to show the output`() {
        tooling.run(ClusterTool.HELM, listOf("list", "-n", "default", "-q"), echo = false)

        verify(remoteOps).executeRemotely(any(), eq("$kubeconfig helm list -n default -q"), eq(false), any())
    }

    @Test
    fun `only the marked arguments are single-quoted and the rest are joined raw`() {
        tooling.run(
            ClusterTool.KUBECTL,
            listOf("get", "pods,services", "-l", "tier in (a,b)", "-n", "ns", "--set", "k=v"),
            shellQuoted = setOf(3),
        )

        assertThat(remoteCommands())
            .containsExactly("$kubeconfig kubectl get pods,services -l 'tier in (a,b)' -n ns --set k=v")
    }

    @Test
    fun `an unmarked argument the shell would split or expand is quoted, and an empty one stays a word`() {
        tooling.run(ClusterTool.HELM, listOf("repo", "add", "my repo", "https://h/c?a=1&b=\$X", ""))

        assertThat(remoteCommands()).containsExactly("$kubeconfig helm repo add 'my repo' 'https://h/c?a=1&b=\$X' ''")
    }

    @Test
    fun `a marked argument with a single quote is escaped so it stays one shell word`() {
        tooling.run(ClusterTool.HELM, listOf("list", "-f", "^it's\$"), shellQuoted = setOf(2))

        assertThat(remoteCommands()).containsExactly("$kubeconfig helm list -f '^it'\\''s\$'")
    }

    @Test
    fun `a marked argument reaches the remote shell as exactly the original value`() {
        val awkward =
            listOf(
                "tier in (a, b)",
                "it's",
                "a\"b",
                "\$HOME `id` \$(id)",
                "semi;colon && pipe | star * glob ? hash # tilde ~",
                "back\\slash",
                "two\nlines",
            )

        awkward.forEach { value ->
            val command = captureCommandFor(value)
            val quoted = command.removePrefix("$kubeconfig helm x ")
            val process = ProcessBuilder("/bin/sh", "-c", "printf %s $quoted").redirectErrorStream(true).start()
            val printed = process.inputStream.readBytes().toString(Charsets.UTF_8)
            process.waitFor()

            assertThat(printed).describedAs("shell reading %s", quoted).isEqualTo(value)
        }
    }

    private fun captureCommandFor(value: String): String {
        val remote: RemoteOperationsService = mock()
        whenever(remote.executeRemotely(any(), any(), any(), any())).doReturn(Response(""))
        SshClusterTooling(remote, stateManager, tempDir.toPath()).run(ClusterTool.HELM, listOf("x", value), shellQuoted = setOf(1))
        return argumentCaptor<String>().also { verify(remote).executeRemotely(any(), it.capture(), any(), any()) }.firstValue
    }

    @Test
    fun `stdin is uploaded and the dash argument becomes the remote path with no redirect`() {
        var uploadedContent: String? = null
        val localPaths = mutableListOf<Path>()
        doAnswer {
            val local = it.getArgument<Path>(1)
            localPaths.add(local)
            uploadedContent = Files.readString(local)
        }.whenever(remoteOps).upload(any(), any(), any())

        tooling.run(ClusterTool.KUBECTL, listOf("apply", "-f", "-"), stdin = "kind: ConfigMap\n")

        val remotePath = argumentCaptor<String>().also { verify(remoteOps).upload(any(), any(), it.capture()) }.firstValue
        assertThat(remotePath).matches("/tmp/easydblab-input-[0-9a-f-]{36}\\.yaml")
        assertThat(uploadedContent).isEqualTo("kind: ConfigMap\n")
        assertThat(remoteCommands()).containsExactly(
            "$kubeconfig kubectl apply -f $remotePath",
            "rm -f $remotePath",
        )
        assertThat(remoteCommands().joinToString(" ")).doesNotContain("<")
        assertThat(localPaths.single().parent).isEqualTo(tempDir.toPath())
    }

    @Test
    fun `helm values on stdin keep the values flag and swap only the dash`() {
        tooling.run(ClusterTool.HELM, listOf("upgrade", "--install", "r", "chart", "--values", "-", "--set", "a=b"), stdin = "x: 1\n")

        val remotePath = argumentCaptor<String>().also { verify(remoteOps).upload(any(), any(), it.capture()) }.firstValue
        assertThat(remoteCommands().first())
            .isEqualTo("$kubeconfig helm upgrade --install r chart --values $remotePath --set a=b")
    }

    @Test
    fun `the upload comes first and the remote file is removed last without echoing`() {
        tooling.run(ClusterTool.KUBECTL, listOf("apply", "-f", "-"), stdin = "x: 1\n")

        val order = inOrder(remoteOps)
        order.verify(remoteOps).upload(any(), any(), any())
        order.verify(remoteOps).executeRemotely(any(), argThat { startsWith(kubeconfig) }, eq(true), any())
        order.verify(remoteOps).executeRemotely(any(), argThat { startsWith("rm -f ") }, eq(false), any())
    }

    @Test
    fun `no staging file is left in the temp directory after a successful run`() {
        tooling.run(ClusterTool.KUBECTL, listOf("apply", "-f", "-"), stdin = "x: 1\n")

        assertThat(tempDirEntries()).isEmpty()
    }

    @Test
    fun `the remote file is removed and the staging file deleted when the command fails`() {
        whenever(remoteOps.executeRemotely(any(), argThat { startsWith(kubeconfig) }, any(), any()))
            .doThrow(RemoteCommandFailedException("c", "", "boom", "summary", exitStatus = 1))

        assertThatThrownBy { tooling.run(ClusterTool.KUBECTL, listOf("apply", "-f", "-"), stdin = "x: 1\n") }
            .isInstanceOf(ClusterToolFailedException::class.java)

        val removal = remoteCommands().single { it.startsWith("rm -f ") }
        assertThat(removal).matches("rm -f /tmp/easydblab-input-[0-9a-f-]{36}\\.yaml")
        assertThat(tempDirEntries()).isEmpty()
    }

    @Test
    fun `a failed upload still cleans up both files`() {
        doThrow(IllegalStateException("scp down")).whenever(remoteOps).upload(any(), any(), any())

        assertThatThrownBy { tooling.run(ClusterTool.KUBECTL, listOf("apply", "-f", "-"), stdin = "x: 1\n") }
            .hasMessageContaining("scp down")

        assertThat(remoteCommands().single()).startsWith("rm -f ")
        assertThat(tempDirEntries()).isEmpty()
    }

    @Test
    fun `a failing cleanup never replaces the tool's own exception`() {
        whenever(remoteOps.executeRemotely(any(), argThat { startsWith(kubeconfig) }, any(), any()))
            .doThrow(RemoteCommandFailedException("c", "", "the real error", "summary", exitStatus = 7))
        whenever(remoteOps.executeRemotely(any(), argThat { startsWith("rm -f ") }, any(), any()))
            .doThrow(IllegalStateException("connection lost"))

        val failure =
            catchThrowableOfType(ClusterToolFailedException::class.java) {
                tooling.run(ClusterTool.KUBECTL, listOf("apply", "-f", "-"), stdin = "x: 1\n")
            }

        assertThat(failure.exitStatus).isEqualTo(7)
        assertThat(failure.stderr).isEqualTo("the real error")
    }

    @Test
    fun `an interrupt during cleanup is not swallowed`() {
        // doAnswer, not doThrow: Mockito rejects a checked exception the Kotlin method does not declare.
        doAnswer { throw InterruptedException("cancelled") }
            .whenever(remoteOps)
            .executeRemotely(any(), argThat { startsWith("rm -f ") }, any(), any())

        try {
            assertThat(tooling.run(ClusterTool.KUBECTL, listOf("apply", "-f", "-"), stdin = "x: 1\n")).isEqualTo("out")
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted() // clear the flag so it cannot leak into the next test on this thread
        }
    }

    @Test
    fun `a failing cleanup after a successful command does not fail the call`() {
        whenever(remoteOps.executeRemotely(any(), argThat { startsWith("rm -f ") }, any(), any()))
            .doThrow(IllegalStateException("connection lost"))

        assertThat(tooling.run(ClusterTool.KUBECTL, listOf("apply", "-f", "-"), stdin = "x: 1\n")).isEqualTo("out")
    }

    @Test
    fun `stdin without exactly one dash argument fails before touching the cluster`() {
        assertThatThrownBy { tooling.run(ClusterTool.KUBECTL, listOf("apply", "-f", "/tmp/m.yaml"), stdin = "x") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { tooling.run(ClusterTool.KUBECTL, listOf("apply", "-f", "-", "-f", "-"), stdin = "x") }
            .isInstanceOf(IllegalArgumentException::class.java)

        verifyNoInteractions(remoteOps)
        assertThat(tempDirEntries()).isEmpty()
    }

    @Test
    fun `a bad stdin call leaves URL credentials out of the message`() {
        assertThatThrownBy {
            tooling.run(ClusterTool.KUBECTL, listOf("apply", "-f", "https://user:s3cret@example.com/m.yaml"), stdin = "x")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("https://***@example.com/m.yaml")
            .hasMessageNotContaining("s3cret")
        verifyNoInteractions(remoteOps)
    }

    @Test
    fun `a shell-quoted index outside the arguments fails before touching the cluster`() {
        assertThatThrownBy { tooling.run(ClusterTool.KUBECTL, listOf("get"), shellQuoted = setOf(5)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("[5]")
        assertThatThrownBy { tooling.run(ClusterTool.KUBECTL, listOf("get"), shellQuoted = setOf(-1)) }
            .isInstanceOf(IllegalArgumentException::class.java)

        verifyNoInteractions(remoteOps)
    }

    @Test
    fun `a dash argument without stdin is passed through untouched`() {
        tooling.run(ClusterTool.KUBECTL, listOf("logs", "-f", "-"))

        verify(remoteOps, never()).upload(any(), any(), any())
        assertThat(remoteCommands()).containsExactly("$kubeconfig kubectl logs -f -")
    }

    @Test
    fun `the control host is read again on every call`() {
        val newControl = clusterHost("control0", "54.9.9.9", "10.0.0.9")

        tooling.run(ClusterTool.KUBECTL, listOf("get", "nodes"))
        saveState(newControl)
        tooling.run(ClusterTool.KUBECTL, listOf("get", "nodes"))

        val hosts =
            argumentCaptor<Host>().also {
                verify(remoteOps, org.mockito.kotlin.times(2)).executeRemotely(it.capture(), any(), any(), any())
            }
        assertThat(hosts.allValues).containsExactly(controlHost.toHost(), newControl.toHost())
    }

    @Test
    fun `a cluster with no control node fails with the same message ObservabilityHttp uses`() {
        stateManager.save(ClusterState(name = "test", versions = mutableMapOf()))

        assertThatThrownBy { tooling.run(ClusterTool.KUBECTL, listOf("get", "nodes")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("No control node found. Please ensure the environment is running.")
        verifyNoInteractions(remoteOps)
    }

    @Test
    fun `a remote failure becomes a cluster tool failure with the same status and stderr`() {
        whenever(remoteOps.executeRemotely(any(), any(), any(), any()))
            .doThrow(RemoteCommandFailedException("c", "some stdout", "error: no such kind \"nosuchkind\"", "summary", exitStatus = 1))

        val failure =
            catchThrowableOfType(ClusterToolFailedException::class.java) {
                tooling.run(ClusterTool.KUBECTL, listOf("get", "nosuchkind"))
            }

        assertThat(failure.tool).isEqualTo(ClusterTool.KUBECTL)
        assertThat(failure.args).containsExactly("get", "nosuchkind")
        assertThat(failure.exitStatus).isEqualTo(1)
        assertThat(failure.stderr).isEqualTo("error: no such kind \"nosuchkind\"")
        assertThat(failure.message)
            .contains("kubectl get nosuchkind", "exit status 1", "control0", "54.1.2.3", "no such kind")
    }

    @Test
    fun `a remote failure keeps the remote stdout, with URL credentials redacted, in the message`() {
        whenever(remoteOps.executeRemotely(any(), any(), any(), any()))
            .doThrow(
                RemoteCommandFailedException(
                    "c",
                    "pulled https://user:s3cret@host/chart.tgz",
                    "Error: no such chart",
                    "summary",
                    exitStatus = 1,
                ),
            )

        val failure =
            catchThrowableOfType(ClusterToolFailedException::class.java) { tooling.run(ClusterTool.HELM, listOf("pull", "chart")) }

        assertThat(failure.stdout).isEqualTo("pulled https://***@host/chart.tgz")
        assertThat(failure.message)
            .contains("stderr:\nError: no such chart", "stdout:\npulled https://***@host/chart.tgz")
            .doesNotContain("s3cret")
    }

    @Test
    fun `the failure location shows the public address, else the private one, else just the alias`() {
        whenever(remoteOps.executeRemotely(any(), any(), any(), any()))
            .doThrow(RemoteCommandFailedException("c", "", "boom", "summary", exitStatus = 1))
        val locations =
            listOf(
                clusterHost("control0", "54.1.2.3", "10.0.0.1"),
                clusterHost("control0", "", "10.0.0.1"),
                clusterHost("control0", "  ", ""),
            ).map { host ->
                saveState(host)
                catchThrowableOfType(ClusterToolFailedException::class.java) { tooling.run(ClusterTool.HELM, listOf("list")) }.location
            }

        assertThat(locations).containsExactly("control0 (54.1.2.3)", "control0 (10.0.0.1)", "control0")
    }

    @Test
    fun `a remote failure with no reported status carries the unknown status`() {
        whenever(remoteOps.executeRemotely(any(), any(), any(), anyOrNull()))
            .doThrow(RemoteCommandFailedException("c", "", "", "summary"))

        val failure =
            catchThrowableOfType(ClusterToolFailedException::class.java) {
                tooling.run(ClusterTool.HELM, listOf("list"))
            }

        assertThat(failure.exitStatus).isEqualTo(Constants.ClusterTooling.UNKNOWN_EXIT_STATUS)
    }
}
