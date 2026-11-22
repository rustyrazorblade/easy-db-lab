package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.File
import java.util.Base64

/**
 * [SshClusterTooling] with the real kubectl and kubeconfig of a K3s container standing in for the
 * control node: each remote command runs through `sh -c` in the container, as SSH runs it on the
 * control node, so the quoting, the `KUBECONFIG` prefix, stdin and the exit status are all real.
 * The K3s image ships kubectl and no helm, so only the kubectl paths run here.
 */
class SshClusterToolingK3sIntegrationTest {
    @TempDir
    lateinit var stateDir: File

    private val namespace = "cluster-tooling-it"
    private val container = ContainerShell()
    private lateinit var kubectl: KubectlService

    @BeforeEach
    fun setup() {
        val stateManager = ClusterStateManager(File(stateDir, "state.json"))
        stateManager.replace(
            ClusterState(
                name = "it",
                hosts = mapOf(ServerType.Control to listOf(ClusterHost("127.0.0.1", "127.0.0.1", "control0", "local"))),
            ),
        )
        kubectl = DefaultKubectlService(SshClusterTooling(container, stateManager))
    }

    @Test
    fun `kubectl through the seam creates, applies, deletes by a set-based selector and reports failures`() {
        val tmpBefore = container.tmpListing()

        kubectl.createNamespace(namespace)
        kubectl.createNamespace(namespace)
        // One round trip per namespace: the manifest travels as the command's stdin.
        assertThat(container.commands).hasSize(2)

        listOf("web", "cache", "db").forEach { tier -> kubectl.applyContent(configMap("cm-$tier", tier)) }
        kubectl.deleteBySelector(listOf("configmap"), "tier in (web, cache)", namespace)

        SharedK3s.client().use { client ->
            assertThat(client.namespaces().withName(namespace).get()).isNotNull
            val left =
                client
                    .configMaps()
                    .inNamespace(namespace)
                    .withLabel("tier")
                    .list()
                    .items
                    .map { it.metadata.name }
            assertThat(left).containsExactly("cm-db")
        }

        val failure =
            catchThrowableOfType(ClusterToolFailedException::class.java) {
                kubectl.delete("nosuchkind", "x", namespace, ignoreNotFound = false)
            }
        assertThat(failure.exitStatus).isEqualTo(1)
        assertThat(failure.stderr).contains("nosuchkind")
        assertThat(failure).hasMessageContaining("kubectl delete nosuchkind/x -n $namespace failed on control0 (127.0.0.1)")

        assertThat(container.tmpListing()).isEqualTo(tmpBefore)
    }

    private fun configMap(
        name: String,
        tier: String,
    ): String =
        Serialization.asYaml(
            ConfigMapBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels("tier", tier)
                .endMetadata()
                .addToData("k", "v")
                .build(),
        )

    /**
     * Runs each command with `sh -c` in the shared K3s container and fails a non-zero exit the way
     * the SSH client does. Input reaches the command as its stdin, piped in base64 so no byte of it
     * is read by the shell.
     */
    private class ContainerShell(
        private val delegate: RemoteOperationsService = mock(),
    ) : RemoteOperationsService by delegate {
        val commands = mutableListOf<String>()

        override fun executeRemotely(
            host: Host,
            command: String,
            output: Boolean,
            secret: Boolean,
        ): Response = run(command, command)

        override fun executeRemotelyWithInput(
            host: Host,
            command: String,
            input: String,
            output: Boolean,
        ): Response {
            val encoded = Base64.getEncoder().encodeToString(input.toByteArray())
            return run(command, "printf %s $encoded | base64 -d | { $command; }")
        }

        fun tmpListing(): String = SharedK3s.exec("ls", "-A", "/tmp").stdout

        private fun run(
            command: String,
            script: String,
        ): Response {
            commands += command
            val result = SharedK3s.exec("sh", "-c", script)
            if (result.exitCode != 0) {
                throw RemoteCommandFailedException(command, result.stdout, result.stderr, "Remote command failed", result.exitCode)
            }
            return Response(result.stdout, result.stderr)
        }
    }
}
