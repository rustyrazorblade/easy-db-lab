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
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
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

        assertThat(commandCaptor.firstValue).contains("kubectl apply --server-side -f https://example.com/manifest.yaml")
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
    fun `listInAllNamespaces returns every object of the type in any namespace, without echoing them`() {
        lookupReturns("cluster.postgresql.cnpg.io/postgres-duckdb\n\ncluster.postgresql.cnpg.io/postgres-postgis\n")

        val found = makeService().listInAllNamespaces(host = testHost, resource = "clusters.postgresql.cnpg.io")

        assertThat(found).containsExactly("cluster.postgresql.cnpg.io/postgres-duckdb", "cluster.postgresql.cnpg.io/postgres-postgis")
        val commands = argumentCaptor<String>()
        verify(mockRemoteOps).executeRemotely(eq(testHost), commands.capture(), eq(false), any())
        assertThat(commands.firstValue)
            .startsWith("KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}")
            .contains("kubectl get clusters.postgresql.cnpg.io --all-namespaces -o name")
    }

    private fun lookupReturns(names: String) {
        whenever(mockRemoteOps.executeRemotely(any(), argThat { contains("kubectl get") }, any(), any()))
            .doReturn(Response(names))
    }

    private fun deleteBySelector() =
        makeService().deleteBySelector(
            host = testHost,
            kinds = listOf("deployment", "pod"),
            selector = "easydblab/kit=memcached",
            namespace = "default",
        )

    @Test
    fun `deleteBySelector deletes exactly the objects the selector matches, by name`() {
        lookupReturns("deployment.apps/memcached\npod/memcached-abc\n")

        deleteBySelector()

        val commands = argumentCaptor<String>()
        verify(mockRemoteOps, times(2)).executeRemotely(eq(testHost), commands.capture(), any(), any())
        assertThat(commands.firstValue)
            .startsWith("KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}")
            .contains("kubectl get deployment,pod -l 'easydblab/kit=memcached' -n default -o name")
        assertThat(commands.secondValue)
            .contains("kubectl delete 'deployment.apps/memcached' 'pod/memcached-abc' -n default --ignore-not-found")
    }

    /**
     * Deleting by a label that matches nothing makes kubectl print a bare "No resources found", so
     * the lookup runs without echoing its output and nothing is deleted when it finds nothing.
     */
    @Test
    fun `deleteBySelector runs no delete and prints nothing when the selector matches nothing`() {
        lookupReturns("")

        deleteBySelector()

        verify(mockRemoteOps, times(1)).executeRemotely(any(), any(), eq(false), any())
        verify(mockRemoteOps, times(1)).executeRemotely(any(), any(), any(), any())
    }

    @Test
    fun `deleteBySelector fails, deleting nothing, when the lookup fails`() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any())).doThrow(RuntimeException("connection refused"))

        assertThatThrownBy { deleteBySelector() }.hasMessageContaining("connection refused")
        verify(mockRemoteOps, times(1)).executeRemotely(any(), any(), any(), any())
    }

    @Test
    fun `deleteBySelector quotes a selector the shell would otherwise split`() {
        lookupReturns("")

        makeService().deleteBySelector(
            host = testHost,
            kinds = listOf("pod"),
            selector = "tier in (web, cache)",
            namespace = "default",
        )

        val commands = argumentCaptor<String>()
        verify(mockRemoteOps).executeRemotely(eq(testHost), commands.capture(), any(), any())
        assertThat(commands.firstValue).contains("-l 'tier in (web, cache)'")
    }

    @Test
    fun `applyContent uploads yaml to remote path and applies it`() {
        val yaml = "apiVersion: v1\nkind: ConfigMap"

        makeService().applyContent(host = testHost, yamlContent = yaml)

        val remotePathCaptor = argumentCaptor<String>()
        verify(mockRemoteOps).upload(eq(testHost), any(), remotePathCaptor.capture())
        val remotePath = remotePathCaptor.firstValue
        assertThat(remotePath).startsWith("/tmp/easydblab-manifest-").endsWith(".yaml")

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps, times(2)).executeRemotely(eq(testHost), commandCaptor.capture(), any(), any())
        assertThat(commandCaptor.allValues[0]).contains("kubectl apply -f $remotePath")
        assertThat(commandCaptor.allValues[1]).isEqualTo("rm -f $remotePath")
    }

    @Test
    fun `applyContent cleans up remote file even when kubectl apply fails`() {
        whenever(mockRemoteOps.executeRemotely(any(), argThat { contains("kubectl apply") }, any(), any()))
            .doThrow(RuntimeException("apply failed"))

        assertThatThrownBy {
            makeService().applyContent(host = testHost, yamlContent = "apiVersion: v1")
        }.isInstanceOf(RuntimeException::class.java)

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps, atLeastOnce()).executeRemotely(eq(testHost), commandCaptor.capture(), any(), any())
        assertThat(commandCaptor.allValues).anyMatch { it.startsWith("rm -f") }
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
