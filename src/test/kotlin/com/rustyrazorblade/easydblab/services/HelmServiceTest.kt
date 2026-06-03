package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

class HelmServiceTest : BaseKoinTest() {
    private lateinit var mockRemoteOps: RemoteOperationsService

    private val testHost =
        Host(
            public = "54.1.2.3",
            private = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<RemoteOperationsService> { mock<RemoteOperationsService>().also { mockRemoteOps = it } }
            },
        )

    @BeforeEach
    fun setup() {
        mockRemoteOps = getKoin().get()
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any())).doReturn(Response(""))
    }

    private fun makeService(): DefaultHelmService = DefaultHelmService(remoteOps = getKoin().get())

    @Test
    fun `repoAdd invokes helm repo add with --force-update then repo update`() {
        makeService().repoAdd(host = testHost, name = "myrepo", url = "https://example.com/charts")

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps, times(2)).executeRemotely(eq(testHost), commandCaptor.capture(), any(), any())

        val commands = commandCaptor.allValues
        assertThat(commands[0]).contains("helm repo add myrepo https://example.com/charts --force-update")
        assertThat(commands[1]).contains("helm repo update myrepo")
    }

    @Test
    fun `repoAdd commands include KUBECONFIG prefix`() {
        makeService().repoAdd(host = testHost, name = "myrepo", url = "https://example.com/charts")

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps, times(2)).executeRemotely(eq(testHost), commandCaptor.capture(), any(), any())

        assertThat(commandCaptor.allValues).allMatch { it.startsWith("KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}") }
    }

    @Test
    fun `upgradeInstall builds correct helm upgrade --install command`() {
        makeService().upgradeInstall(
            host = testHost,
            release = "myapp",
            chart = "myrepo/myapp",
            namespace = "default",
            version = "1.2.3",
            values = mapOf("key1" to "val1", "key2" to "val2"),
        )

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps).executeRemotely(eq(testHost), commandCaptor.capture(), any(), any())

        val command = commandCaptor.firstValue
        assertThat(command).contains("helm upgrade --install myapp myrepo/myapp")
        assertThat(command).contains("--namespace default")
        assertThat(command).contains("--version 1.2.3")
        assertThat(command).contains("--set key1=val1")
        assertThat(command).contains("--set key2=val2")
        assertThat(command).doesNotContain("--wait")
        assertThat(command).doesNotContain("--create-namespace")
    }

    @Test
    fun `upgradeInstall with wait=true includes --wait and --timeout`() {
        makeService().upgradeInstall(
            host = testHost,
            release = "myapp",
            chart = "myrepo/myapp",
            namespace = "default",
            wait = true,
            timeout = "10m",
        )

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps).executeRemotely(eq(testHost), commandCaptor.capture(), any(), any())

        assertThat(commandCaptor.firstValue).contains("--wait --timeout 10m")
    }

    @Test
    fun `upgradeInstall with createNamespace=true includes --create-namespace`() {
        makeService().upgradeInstall(
            host = testHost,
            release = "myapp",
            chart = "myrepo/myapp",
            namespace = "mynamespace",
            createNamespace = true,
        )

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps).executeRemotely(eq(testHost), commandCaptor.capture(), any(), any())

        assertThat(commandCaptor.firstValue).contains("--create-namespace")
    }

    @Test
    fun `upgradeInstall with valuesFile uploads file and passes remote path to helm`() {
        val valuesFile = File(tempDir, "values.yaml").also { it.writeText("key: value") }

        makeService().upgradeInstall(
            host = testHost,
            release = "myapp",
            chart = "myrepo/myapp",
            namespace = "default",
            valuesFile = valuesFile,
        )

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps, atLeastOnce()).executeRemotely(eq(testHost), commandCaptor.capture(), any(), any())

        assertThat(commandCaptor.allValues).anyMatch { it.contains("--values /tmp/helm-values-") }
    }

    @Test
    fun `uninstall invokes helm uninstall with --ignore-not-found`() {
        makeService().uninstall(host = testHost, release = "myapp", namespace = "default")

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps).executeRemotely(eq(testHost), commandCaptor.capture(), any(), any())

        assertThat(commandCaptor.firstValue).contains("helm uninstall myapp -n default --ignore-not-found")
    }

    @Test
    fun `upgradeInstall propagates exception from executeRemotely`() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any()))
            .doThrow(RuntimeException("SSH command failed"))

        assertThatThrownBy {
            makeService().upgradeInstall(
                host = testHost,
                release = "r",
                chart = "c",
                namespace = "n",
            )
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("SSH command failed")
    }

    @Test
    fun `releaseExists returns true when helm list returns non-empty text`() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any()))
            .doReturn(Response("myapp"))

        val result = makeService().releaseExists(host = testHost, release = "myapp", namespace = "default")

        assertThat(result).isTrue()
    }

    @Test
    fun `releaseExists returns false when helm list returns empty text`() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any()))
            .doReturn(Response(""))

        val result = makeService().releaseExists(host = testHost, release = "noapp", namespace = "default")

        assertThat(result).isFalse()
    }
}
