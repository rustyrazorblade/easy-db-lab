package com.rustyrazorblade.easydblab.kubernetes

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WorkspaceKubeconfigTest {
    @TempDir
    lateinit var workspace: File

    @Test
    fun `file is the absolute kubeconfig of the workspace whatever the path form`() {
        val relative = File(workspace, "sub/..").also { File(workspace, "sub").mkdirs() }

        assertThat(WorkspaceKubeconfig.file(relative).isAbsolute).isTrue()
        assertThat(WorkspaceKubeconfig.file(relative).name).isEqualTo("kubeconfig")
        assertThat(WorkspaceKubeconfig.file(workspace).parentFile.canonicalFile).isEqualTo(workspace.canonicalFile)
    }

    @Test
    fun `exists and require follow whether the workspace holds a kubeconfig`() {
        assertThat(WorkspaceKubeconfig.exists(workspace)).isFalse()
        assertThatThrownBy { WorkspaceKubeconfig.require(workspace) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(WorkspaceKubeconfig.file(workspace).path)

        File(workspace, "kubeconfig").writeText("apiVersion: v1\n")

        assertThat(WorkspaceKubeconfig.exists(workspace)).isTrue()
        assertThat(WorkspaceKubeconfig.require(workspace)).isEqualTo(WorkspaceKubeconfig.file(workspace))
    }

    @Test
    fun `a directory named kubeconfig is not a kubeconfig`() {
        File(workspace, "kubeconfig").mkdirs()

        assertThat(WorkspaceKubeconfig.exists(workspace)).isFalse()
    }
}
