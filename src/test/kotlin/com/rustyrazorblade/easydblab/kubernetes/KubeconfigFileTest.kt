package com.rustyrazorblade.easydblab.kubernetes

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * A kubeconfig that cannot be read or parsed fails every K8s call, so the failure must name the file
 * instead of surfacing an opaque YAML error, and must never quote the file's text, which holds client
 * keys and tokens.
 */
class KubeconfigFileTest {
    @TempDir
    lateinit var tempDir: File

    /** A kubeconfig cut off mid-way through a quoted server URL. */
    private fun truncatedKubeconfig(): File =
        File(tempDir, "kubeconfig").apply {
            writeText(
                """
                |apiVersion: v1
                |kind: Config
                |clusters:
                |- name: c
                |  cluster:
                |    server: "https://10.0
                """.trimMargin(),
            )
        }

    @Test
    fun `a truncated kubeconfig fails naming the path and the kind of failure, never quoting its text`() {
        val file = truncatedKubeconfig()

        val thrown = catchThrowable { currentContextOf(file.toPath()) }

        assertThat(thrown)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(file.absolutePath)
            .hasMessageNotContaining("https://10.0")
            .hasNoCause()
    }

    @Test
    fun `a missing kubeconfig fails as an IOException naming the path, with a file-not-found cause`() {
        val missing = File(tempDir, "no-such-dir/kubeconfig")

        assertThatThrownBy { currentContextOf(missing.toPath()) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining(missing.absolutePath)
            .hasCauseInstanceOf(FileNotFoundException::class.java)
    }

    @Test
    fun `loading a client config from a truncated kubeconfig fails naming the path`() {
        val file = truncatedKubeconfig()

        assertThatThrownBy { KubeApi(file.toPath(), "c").loadConfig() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(file.absolutePath)
    }
}
