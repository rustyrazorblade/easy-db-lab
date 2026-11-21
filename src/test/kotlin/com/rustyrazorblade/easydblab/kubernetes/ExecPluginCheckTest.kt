package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Fabric8 sends a request with no credential when a kubeconfig's exec auth plugin cannot be run,
 * so the check must find a missing plugin before any client is built, and name the plugin, the
 * user, the context and where it looked.
 */
class ExecPluginCheckTest {
    @TempDir
    lateinit var tempDir: File

    private val emptyPath get() = File(tempDir, "empty-bin").apply { mkdirs() }.absolutePath

    private fun kubeconfig(userBlock: String): Path =
        File(tempDir, "kubeconfig")
            .apply {
                writeText(
                    """
                    |apiVersion: v1
                    |kind: Config
                    |clusters:
                    |- name: c
                    |  cluster:
                    |    server: https://10.0.0.1:6443
                    |contexts:
                    |- name: lab
                    |  context:
                    |    cluster: c
                    |    user: eks-user
                    |current-context: lab
                    |users:
                    |- name: eks-user
                    |  user:
                    |$userBlock
                    """.trimMargin(),
                )
            }.toPath()

    private fun execUser(
        command: String,
        installHint: String? = null,
    ): String =
        listOfNotNull(
            "    exec:",
            "      apiVersion: client.authentication.k8s.io/v1beta1",
            "      command: $command",
            "      args: [\"eks\", \"get-token\"]",
            installHint?.let { "      installHint: \"$it\"" },
        ).joinToString("\n")

    /** Checks [file] as `loadConfig()` does, with the kubeconfig parsed once and passed in. */
    private fun ExecPluginCheck.checkFile(
        file: Path,
        context: String,
    ) = check(file, parseKubeconfig(file), context)

    private fun executable(
        dir: File,
        name: String,
    ): File =
        File(dir, name).apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }

    @Test
    fun `a bare plugin missing from PATH fails naming the plugin, user, context and PATH`() {
        val path = "$emptyPath${File.pathSeparator}/nonexistent"

        val thrown =
            catchThrowableOfType(ExecPluginNotFoundException::class.java) {
                ExecPluginCheck(path).checkFile(kubeconfig(execUser("aws")), "lab")
            }

        assertThat(thrown.plugin).isEqualTo("aws")
        assertThat(thrown.user).isEqualTo("eks-user")
        assertThat(thrown.context).isEqualTo("lab")
        assertThat(thrown.searched).isEqualTo(path)
        assertThat(thrown.message)
            .contains("aws")
            .contains("eks-user")
            .contains("lab")
            .contains(path)
    }

    @Test
    fun `the handler reports a missing plugin as credentials, with the install hint as the remedy`() {
        val thrown =
            catchThrowableOfType(ExecPluginNotFoundException::class.java) {
                ExecPluginCheck(emptyPath).checkFile(kubeconfig(execUser("aws", installHint = "Install the AWS CLI v2")), "lab")
            }

        val description = KubernetesErrorHandler().describe(thrown)

        assertThat(description?.kind).isEqualTo(ProviderErrorKind.CREDENTIALS)
        assertThat(description?.remedy).isEqualTo("Install the AWS CLI v2")
        assertThat(description?.detail).contains("aws").contains(emptyPath)
    }

    @Test
    fun `without an install hint the remedy says to install the plugin or put it on PATH`() {
        val thrown =
            catchThrowableOfType(ExecPluginNotFoundException::class.java) {
                ExecPluginCheck(emptyPath).checkFile(kubeconfig(execUser("kubelogin")), "lab")
            }

        assertThat(KubernetesErrorHandler().describe(thrown)?.remedy).contains("Install kubelogin").contains("PATH")
    }

    @Test
    fun `a plugin found on PATH passes`() {
        val bin = File(tempDir, "bin")
        executable(bin, "gke-gcloud-auth-plugin")

        assertThatCode {
            ExecPluginCheck("$emptyPath${File.pathSeparator}${bin.absolutePath}")
                .checkFile(kubeconfig(execUser("gke-gcloud-auth-plugin")), "lab")
        }.doesNotThrowAnyException()
    }

    @Test
    fun `a relative plugin path resolves against the kubeconfig's directory`() {
        executable(File(tempDir, "bin"), "token-helper")

        assertThatCode { ExecPluginCheck(emptyPath).checkFile(kubeconfig(execUser("./bin/token-helper")), "lab") }
            .doesNotThrowAnyException()
    }

    @Test
    fun `a missing relative plugin names the resolved path`() {
        val thrown =
            catchThrowableOfType(ExecPluginNotFoundException::class.java) {
                ExecPluginCheck(emptyPath).checkFile(kubeconfig(execUser("./bin/token-helper")), "lab")
            }

        assertThat(thrown.searched).isEqualTo(File(tempDir, "bin/token-helper").canonicalPath)
    }

    @Test
    fun `a bare plugin on PATH without the execute bit is not found`() {
        val bin = File(tempDir, "bin")
        executable(bin, "aws").setExecutable(false)

        assertThatThrownBy { ExecPluginCheck(bin.absolutePath).checkFile(kubeconfig(execUser("aws")), "lab") }
            .isInstanceOf(ExecPluginNotFoundException::class.java)
    }

    @Test
    fun `a directory on PATH named like a bare plugin is not found`() {
        val bin = File(tempDir, "bin")
        File(bin, "aws").mkdirs()

        assertThatThrownBy { ExecPluginCheck(bin.absolutePath).checkFile(kubeconfig(execUser("aws")), "lab") }
            .isInstanceOf(ExecPluginNotFoundException::class.java)
    }

    @Test
    fun `a directory at a path-style plugin command is not found`() {
        File(tempDir, "bin/token-helper").mkdirs()

        val thrown =
            catchThrowableOfType(ExecPluginNotFoundException::class.java) {
                ExecPluginCheck(emptyPath).checkFile(kubeconfig(execUser("./bin/token-helper")), "lab")
            }

        assertThat(thrown.searched).isEqualTo(File(tempDir, "bin/token-helper").canonicalPath)
    }

    @Test
    fun `a path-style plugin without the execute bit is not found`() {
        executable(File(tempDir, "bin"), "token-helper").setExecutable(false)

        assertThatThrownBy { ExecPluginCheck(emptyPath).checkFile(kubeconfig(execUser("./bin/token-helper")), "lab") }
            .isInstanceOf(ExecPluginNotFoundException::class.java)
    }

    @Test
    fun `a user with no exec entry is not checked`() {
        assertThatCode { ExecPluginCheck(emptyPath).checkFile(kubeconfig("    token: abc"), "lab") }
            .doesNotThrowAnyException()
    }

    @Test
    fun `loading the kubeconfig fails on a missing plugin before any client is built`() {
        val api = KubeApi(kubeconfig(execUser("aws")), "lab")

        assertThatThrownBy { api.loadConfig(ExecPluginCheck(emptyPath)) }
            .isInstanceOf(ExecPluginNotFoundException::class.java)
    }
}
