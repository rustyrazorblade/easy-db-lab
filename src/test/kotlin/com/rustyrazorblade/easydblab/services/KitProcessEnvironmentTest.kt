package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CLUSTER_STATE
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** [KitProcessEnvironment] is the one launch seam every kit process goes through. */
internal class KitProcessEnvironmentTest {
    @TempDir
    lateinit var workspace: File

    private val environment: KitProcessEnvironment
        get() = KitProcessEnvironment(ToolWrapperInstaller(), ClusterStateManager(File(workspace, CLUSTER_STATE)))
    private val kubeconfig: File get() = File(workspace, Constants.K3s.LOCAL_KUBECONFIG)
    private val bin: File get() = File(workspace, Constants.ToolWrappers.DIRECTORY)

    @BeforeEach
    fun writeKubeconfig() {
        kubeconfig.writeText("apiVersion: v1\nkind: Config\n")
    }

    @Test
    fun `PATH starts with the workspace bin followed by the PATH the builder inherited`() {
        val builder = ProcessBuilder("true").also { it.environment()["PATH"] = "/opt/tools:/usr/bin" }

        environment.applyTo(builder, workspace, emptyMap())

        assertThat(builder.environment()["PATH"]).isEqualTo("${bin.absolutePath}${File.pathSeparator}/opt/tools:/usr/bin")
    }

    @Test
    fun `KUBECONFIG is the absolute workspace kubeconfig and replaces a relative one in the variables`() {
        val builder = ProcessBuilder("true")

        environment.applyTo(builder, workspace, mapOf("KUBECONFIG" to "kubeconfig", "CLUSTER_NAME" to "lab"))

        assertThat(builder.environment()["KUBECONFIG"]).isEqualTo(kubeconfig.absolutePath)
        assertThat(builder.environment()["CLUSTER_NAME"]).isEqualTo("lab")
    }

    @Test
    fun `a PATH in the variables does not displace the wrappers`() {
        val builder = ProcessBuilder("true").also { it.environment()["PATH"] = "/usr/bin" }

        environment.applyTo(builder, workspace, mapOf("PATH" to "/elsewhere"))

        assertThat(builder.environment()["PATH"]).startsWith(bin.absolutePath + File.pathSeparator)
    }

    @Test
    fun `a missing kubeconfig fails before anything is written or started`() {
        kubeconfig.delete()

        assertThatThrownBy { environment.applyTo(ProcessBuilder("true"), workspace, emptyMap()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(kubeconfig.absolutePath)

        assertThat(bin).doesNotExist()
    }

    @Test
    fun `the wrappers exist afterwards`() {
        environment.applyTo(ProcessBuilder("true"), workspace, emptyMap())

        assertThat(bin.list()).containsExactlyInAnyOrderElementsOf(
            Constants.ToolWrappers.WRAPPED_TOOLS + Constants.ToolWrappers.TUNNEL_SCRIPT + Constants.ToolWrappers.KIT_LIBRARY +
                Constants.ToolWrappers.MARKER,
        )
    }

    @Test
    fun `EDL_KIT_LIB holds the absolute path of the workspace kit library, which exists`() {
        val builder = ProcessBuilder("true")

        environment.applyTo(builder, workspace, mapOf(Constants.Kit.LIBRARY_ENV to "elsewhere.sh"))

        val library = File(bin, Constants.ToolWrappers.KIT_LIBRARY)
        assertThat(builder.environment()[Constants.Kit.LIBRARY_ENV]).isEqualTo(library.absolutePath)
        assertThat(library).isFile()
    }

    @Test
    fun `EDL_NODE_SSH is false when the recorded set lacks NODE_SSH, even with an sshConfig`() {
        File(workspace, DefaultClusterConfigurationService.SSH_CONFIG_FILE).writeText("Host db0\n")
        recordCapabilities(NodeCapability.entries.toSet() - NodeCapability.NODE_SSH)
        val builder = ProcessBuilder("true")

        environment.applyTo(builder, workspace, mapOf(Constants.Kit.NODE_SSH_ENV to "true"))

        assertThat(builder.environment()[Constants.Kit.NODE_SSH_ENV]).isEqualTo("false")
    }

    @Test
    fun `EDL_NODE_SSH is true on an AWS cluster that records no set`() {
        ClusterStateManager(File(workspace, CLUSTER_STATE)).save(ClusterState(name = "lab", versions = mutableMapOf()))
        val builder = ProcessBuilder("true").also { it.environment()[Constants.Kit.NODE_SSH_ENV] = "false" }

        environment.applyTo(builder, workspace, mapOf(Constants.Kit.NODE_SSH_ENV to "false"))

        assertThat(builder.environment()[Constants.Kit.NODE_SSH_ENV]).isEqualTo("true")
    }

    @Test
    fun `EDL_NODE_SSH is true when the recorded set has NODE_SSH, without an sshConfig`() {
        recordCapabilities(setOf(NodeCapability.NODE_SSH))
        val builder = ProcessBuilder("true")

        environment.applyTo(builder, workspace, emptyMap())

        assertThat(builder.environment()[Constants.Kit.NODE_SSH_ENV]).isEqualTo("true")
    }

    @Test
    fun `EDL_NODE_SSH is true and the launch goes ahead when there is no state json`() {
        val builder = ProcessBuilder("true")

        environment.applyTo(builder, workspace, emptyMap())

        assertThat(builder.environment()[Constants.Kit.NODE_SSH_ENV]).isEqualTo("true")
    }

    @Test
    fun `an unreadable state json does not stop the launch, and EDL_NODE_SSH reads as AWS`() {
        File(workspace, CLUSTER_STATE).writeText("{ not json")
        val builder = ProcessBuilder("true")

        environment.applyTo(builder, workspace, emptyMap())

        assertThat(builder.environment()[Constants.Kit.NODE_SSH_ENV]).isEqualTo("true")
        assertThat(builder.environment()["KUBECONFIG"]).isEqualTo(kubeconfig.absolutePath)
    }

    private fun recordCapabilities(capabilities: Set<NodeCapability>) {
        ClusterStateManager(File(workspace, CLUSTER_STATE))
            .save(ClusterState(name = "lab", versions = mutableMapOf(), capabilities = capabilities))
    }

    @Test
    fun `the launched process runs the wrapper when it calls kubectl`() {
        val builder = ProcessBuilder("/bin/sh", "-c", "command -v kubectl")

        val process = environment.applyTo(builder, workspace, emptyMap()).start()

        assertThat(
            process.inputStream
                .bufferedReader()
                .readText()
                .trim(),
        ).isEqualTo(File(bin, "kubectl").absolutePath)
        assertThat(process.waitFor()).isZero()
    }
}
