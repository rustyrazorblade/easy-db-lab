package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.sidecar.SidecarManifestBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for SidecarService K8s-based implementation.
 *
 * Verifies that deploy, rolloutRestart, and remove delegate correctly to K8sService
 * with resources built by SidecarManifestBuilder.
 */
class SidecarServiceTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var sidecarService: SidecarService

    private val testControlHost =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<K8sService> { mockK8sService }
                factory { SidecarManifestBuilder() }
                factory<SidecarService> { DefaultSidecarService(get(), get(), get()) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockK8sService = mock()
        sidecarService = getKoin().get()
        whenever(mockK8sService.applyResource(any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.rolloutRestartDaemonSet(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.deleteResourcesByLabel(any(), any(), any(), any())).thenReturn(Result.success(Unit))
    }

    @Test
    fun `deploy should apply all DaemonSet resources`() {
        val result = sidecarService.deploy(testControlHost)
        assertThat(result.isSuccess).isTrue()
        verify(mockK8sService).applyResource(eq(testControlHost), any())
    }

    @Test
    fun `deploy should return failure when applyResource fails`() {
        whenever(mockK8sService.applyResource(any(), any()))
            .thenReturn(Result.failure(RuntimeException("K8s unavailable")))
        val result = sidecarService.deploy(testControlHost)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("K8s unavailable")
    }

    @Test
    fun `rolloutRestart should call rolloutRestartDaemonSet with correct name`() {
        val result = sidecarService.rolloutRestart(testControlHost)
        assertThat(result.isSuccess).isTrue()
        verify(mockK8sService).rolloutRestartDaemonSet(testControlHost, "cassandra-sidecar", "default")
    }

    @Test
    fun `rolloutRestart should return failure when K8s operation fails`() {
        whenever(mockK8sService.rolloutRestartDaemonSet(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("Rollout failed")))
        val result = sidecarService.rolloutRestart(testControlHost)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("Rollout failed")
    }

    @Test
    fun `remove should call deleteResourcesByLabel with cassandra-sidecar label`() {
        val result = sidecarService.remove(testControlHost)
        assertThat(result.isSuccess).isTrue()
        verify(mockK8sService).deleteResourcesByLabel(
            testControlHost,
            "default",
            "app.kubernetes.io/name",
            listOf("cassandra-sidecar"),
        )
    }

    @Test
    fun `remove should return failure when deleteResourcesByLabel fails`() {
        whenever(mockK8sService.deleteResourcesByLabel(any(), any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("Delete failed")))
        val result = sidecarService.remove(testControlHost)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("Delete failed")
    }

    @Test
    fun `SidecarManifestBuilder should produce a DaemonSet targeting db nodes`() {
        val builder = SidecarManifestBuilder()
        val resources = builder.buildAllResources()
        assertThat(resources).hasSize(1)
        val daemonSet = resources.first() as DaemonSet
        assertThat(daemonSet.metadata.name).isEqualTo("cassandra-sidecar")
        assertThat(daemonSet.spec.template.spec.nodeSelector).containsEntry("type", "db")
        assertThat(daemonSet.spec.template.spec.hostNetwork).isTrue()
        val container = daemonSet.spec.template.spec.containers.first()
        assertThat(container.image).isEqualTo("ghcr.io/apache/cassandra-sidecar:latest")
        val javaOptsEnv = container.env.find { it.name == "JAVA_OPTS" }
        assertThat(javaOptsEnv).isNotNull
        assertThat(javaOptsEnv!!.value)
            .contains("-Dsidecar.config=file:///etc/cassandra-sidecar/cassandra-sidecar.yaml")
            .contains("\$(CONTROL_NODE_IP)")
            .contains("\$(HOSTNAME)")
        val mountPaths = container.volumeMounts.map { it.mountPath }
        assertThat(mountPaths).contains(
            "/etc/cassandra-sidecar",
            "/mnt/db1/cassandra",
            "/usr/local/otel",
            "/usr/local/pyroscope",
        )
    }
}
