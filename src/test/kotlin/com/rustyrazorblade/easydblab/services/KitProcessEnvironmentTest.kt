package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
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

    private val environment = KitProcessEnvironment(ToolWrapperInstaller())
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
            Constants.ToolWrappers.TOOLS + Constants.ToolWrappers.TUNNEL_SCRIPT + Constants.ToolWrappers.MARKER,
        )
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
