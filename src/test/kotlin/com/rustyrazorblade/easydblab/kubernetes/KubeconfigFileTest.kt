package com.rustyrazorblade.easydblab.kubernetes

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A kubeconfig that cannot be parsed fails every K8s call, so the failure must name the file and the
 * parse problem instead of surfacing an opaque YAML error.
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
    fun `a truncated kubeconfig fails naming the path and the parse cause`() {
        val file = truncatedKubeconfig()

        val thrown = catchThrowable { currentContextOf(file.toPath()) }

        assertThat(thrown)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(file.absolutePath)
        val cause = thrown.cause
        assertThat(cause).isNotNull()
        assertThat(thrown.message).contains(
            cause
                ?.message
                .orEmpty()
                .lines()
                .first(),
        )
    }

    @Test
    fun `loading a client config from a truncated kubeconfig fails naming the path`() {
        val file = truncatedKubeconfig()

        assertThatThrownBy { KubeApi(file.toPath(), "c").loadConfig() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(file.absolutePath)
    }
}
