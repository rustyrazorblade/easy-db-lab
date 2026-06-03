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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class KubectlServiceTest : BaseKoinTest() {
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

    private fun makeService(): DefaultKubectlService = DefaultKubectlService(remoteOps = getKoin().get())

    @Test
    fun `applyUrl invokes kubectl apply -f with the url`() {
        makeService().applyUrl(host = testHost, url = "https://example.com/manifest.yaml")

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps).executeRemotely(eq(testHost), commandCaptor.capture(), any(), any())

        assertThat(commandCaptor.firstValue).contains("kubectl apply -f https://example.com/manifest.yaml")
    }

    @Test
    fun `applyUrl command includes KUBECONFIG prefix`() {
        makeService().applyUrl(host = testHost, url = "https://example.com/manifest.yaml")

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps).executeRemotely(eq(testHost), commandCaptor.capture(), any(), any())

        assertThat(commandCaptor.firstValue).startsWith("KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}")
    }

    @Test
    fun `applyKustomize invokes kubectl apply -k with the url`() {
        makeService().applyKustomize(host = testHost, url = "https://example.com/kustomize")

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps).executeRemotely(eq(testHost), commandCaptor.capture(), any(), any())

        assertThat(commandCaptor.firstValue).contains("kubectl apply -k https://example.com/kustomize")
    }

    @Test
    fun `wait invokes kubectl wait with correct condition kind name namespace and timeout`() {
        makeService().wait(
            host = testHost,
            kind = "Deployment",
            name = "myapp",
            condition = "Available",
            namespace = "mynamespace",
            timeout = "120s",
        )

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps).executeRemotely(eq(testHost), commandCaptor.capture(), any(), any())

        val command = commandCaptor.firstValue
        assertThat(command).contains("kubectl wait --for=condition=Available Deployment/myapp")
        assertThat(command).contains("-n mynamespace")
        assertThat(command).contains("--timeout 120s")
    }

    @Test
    fun `delete invokes kubectl delete with --ignore-not-found when flag is true`() {
        makeService().delete(
            host = testHost,
            kind = "Pod",
            name = "mypod",
            namespace = "default",
            ignoreNotFound = true,
        )

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps).executeRemotely(eq(testHost), commandCaptor.capture(), any(), any())

        assertThat(commandCaptor.firstValue).contains("kubectl delete Pod/mypod -n default --ignore-not-found")
    }

    @Test
    fun `delete omits --ignore-not-found when flag is false`() {
        makeService().delete(
            host = testHost,
            kind = "Pod",
            name = "mypod",
            namespace = "default",
            ignoreNotFound = false,
        )

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps).executeRemotely(eq(testHost), commandCaptor.capture(), any(), any())

        val command = commandCaptor.firstValue
        assertThat(command).contains("kubectl delete Pod/mypod -n default")
        assertThat(command).doesNotContain("--ignore-not-found")
    }

    @Test
    fun `applyUrl propagates exception from executeRemotely`() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any()))
            .doThrow(RuntimeException("SSH command failed"))

        assertThatThrownBy {
            makeService().applyUrl(host = testHost, url = "https://example.com/manifest.yaml")
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("SSH command failed")
    }
}
