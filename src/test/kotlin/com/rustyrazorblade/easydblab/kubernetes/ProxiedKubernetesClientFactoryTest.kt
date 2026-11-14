package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.client.Config
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import java.io.File

/**
 * The factory loads the kubeconfig by path with an explicit context, and sets the SOCKS proxy on the
 * client it builds only.
 */
@ResourceLock(Constants.Proxy.PORT_PROPERTY)
@ResourceLock(Config.KUBERNETES_KUBECONFIG_FILE)
class ProxiedKubernetesClientFactoryTest {
    @TempDir
    lateinit var tempDir: File

    private val factory = ProxiedKubernetesClientFactory()

    private val twoContextKubeconfig =
        """
        apiVersion: v1
        clusters:
        - cluster:
            server: https://first.example:6443
            insecure-skip-tls-verify: true
          name: first
        - cluster:
            server: https://second.example:6443
            insecure-skip-tls-verify: true
          name: second
        contexts:
        - context:
            cluster: first
            user: admin
          name: first-context
        - context:
            cluster: second
            user: admin
          name: second-context
        current-context: first-context
        kind: Config
        preferences: {}
        users:
        - name: admin
          user:
            token: fake-token-for-testing
        """.trimIndent()

    private fun writeKubeconfig(
        content: String = twoContextKubeconfig,
        dir: File = tempDir,
    ): java.nio.file.Path {
        val file = File(dir, "kubeconfig")
        file.writeText(content)
        return file.toPath()
    }

    @BeforeEach
    @AfterEach
    fun clearSystemProperties() {
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
        System.clearProperty(Config.KUBERNETES_KUBECONFIG_FILE)
    }

    @Test
    fun `httpsProxy is null when the proxy port is not published (Tailscale or proxy not started)`() {
        factory.createClient(KubeApi(writeKubeconfig(), "first-context")).use { client ->
            assertThat(client.configuration.httpsProxy).isNull()
        }
    }

    @Test
    fun `httpsProxy is set to socks5 when the proxy port is published`() {
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "1080")
        factory.createClient(KubeApi(writeKubeconfig(), "first-context")).use { client ->
            assertThat(client.configuration.httpsProxy).isEqualTo("socks5://127.0.0.1:1080")
        }
    }

    @Test
    fun `the named context is used, not the kubeconfig's current-context`() {
        factory.createClient(KubeApi(writeKubeconfig(), "second-context")).use { client ->
            assertThat(client.configuration.masterUrl).startsWith("https://second.example:6443")
        }
    }

    @Test
    fun `a context the kubeconfig does not hold fails naming the context, the path and the recovery`() {
        val path = writeKubeconfig()

        assertThatThrownBy { factory.createClient(KubeApi(path, "missing-context")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("missing-context")
            .hasMessageContaining(path.toFile().absolutePath)
            .hasMessageContaining("first-context")
            .hasMessageContaining("Re-run `up`")
    }

    @Test
    fun `a kubeconfig named by the JVM kubeconfig property is ignored`() {
        val elsewhere = File(tempDir, "elsewhere").apply { mkdirs() }
        val other = writeKubeconfig(twoContextKubeconfig.replace("first.example", "elsewhere.example"), elsewhere)
        System.setProperty(Config.KUBERNETES_KUBECONFIG_FILE, other.toString())

        factory.createClient(KubeApi(writeKubeconfig(), "first-context")).use { client ->
            assertThat(client.configuration.masterUrl).startsWith("https://first.example:6443")
        }
    }

    @Test
    fun `a kubeconfig named by the KUBECONFIG env var is ignored`() {
        val elsewhere = File(tempDir, "elsewhere").apply { mkdirs() }
        val other = writeKubeconfig(twoContextKubeconfig.replace("first.example", "elsewhere.example"), elsewhere)
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process =
            ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                MasterUrlProbe::class.java.name,
                writeKubeconfig().toString(),
                "first-context",
            ).redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { it.environment()["KUBECONFIG"] = other.toString() }
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }

        assertThat(process.waitFor()).isZero()
        assertThat(output).startsWith("https://first.example:6443")
    }

    @Test
    fun `a relative certificate path resolves against the kubeconfig's directory`() {
        val workspace = File(tempDir, "workspace").apply { mkdirs() }
        val ca = File(workspace, "certs/ca.crt").apply { parentFile.mkdirs() }
        ca.writeBytes(requireNotNull(javaClass.getResourceAsStream("test-ca.crt")).use { it.readBytes() })
        val kubeconfig =
            writeKubeconfig(
                """
                apiVersion: v1
                clusters:
                - cluster:
                    server: https://cert.example:6443
                    certificate-authority: certs/ca.crt
                  name: c
                contexts:
                - context:
                    cluster: c
                    user: u
                  name: x
                current-context: x
                kind: Config
                users:
                - name: u
                  user:
                    token: t
                """.trimIndent(),
                workspace,
            )

        factory.createClient(KubeApi(kubeconfig, "x")).use { client ->
            assertThat(File(client.configuration.caCertFile).canonicalPath).isEqualTo(ca.canonicalPath)
        }
    }

    @Test
    fun `an exec auth plugin named by a relative path runs from the kubeconfig's directory`() {
        val workspace = File(tempDir, "workspace").apply { mkdirs() }
        File(workspace, "bin/fake-auth").apply {
            parentFile.mkdirs()
            writeText(
                """
                |#!/bin/sh
                |printf '%s' '{"apiVersion":"client.authentication.k8s.io/v1","kind":"ExecCredential","status":{"token":"exec-plugin-token"}}'
                |
                """.trimMargin(),
            )
            setExecutable(true)
        }
        val kubeconfig =
            writeKubeconfig(
                """
                apiVersion: v1
                clusters:
                - cluster:
                    server: https://exec.example:6443
                    insecure-skip-tls-verify: true
                  name: c
                contexts:
                - context:
                    cluster: c
                    user: u
                  name: x
                current-context: x
                kind: Config
                users:
                - name: u
                  user:
                    exec:
                      apiVersion: client.authentication.k8s.io/v1
                      command: ./bin/fake-auth
                      interactiveMode: Never
                """.trimIndent(),
                workspace,
            )

        factory.createClient(KubeApi(kubeconfig, "x")).use { client ->
            assertThat(client.configuration.autoOAuthToken).isEqualTo("exec-plugin-token")
        }
    }
}
