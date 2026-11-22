package com.rustyrazorblade.easydblab.configuration.tenancy

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.kubestatemetrics.KubeStateMetricsManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelHostCollectorManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.HasMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

/**
 * Builds every ClusterRole and ClusterRoleBinding the stack names with [ClusterScopedName], for a
 * cluster whose id is a full 36-character UUID. [ClusterScopedName.of] refuses a name over the
 * Kubernetes limit, so a base made too long fails here, at test time, instead of partway through a
 * deploy. A new caller of [InstallScope.clusterScoped] belongs in [clusterScopedObjects].
 */
class ClusterScopedNameCallersTest : BaseKoinTest() {
    private lateinit var templateService: TemplateService

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager>() }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        val stateManager: ClusterStateManager = getKoin().get()
        whenever(stateManager.load()).thenReturn(ClusterState(name = "test-cluster", hosts = mutableMapOf()))
        templateService = getKoin().get()
    }

    @Test
    fun `every cluster-scoped name fits the Kubernetes limit with a 36-character cluster id`() {
        val clusterId = UUID.randomUUID().toString()
        val scope = InstallScope(K8sNamespace(Constants.ContractDefaults.OWNED_NAMESPACE), clusterId)

        val names = clusterScopedObjects(scope).map { it.metadata.name }

        assertThat(clusterId).hasSize(UUID_LENGTH)
        assertThat(names)
            .hasSize(EXPECTED_OBJECTS)
            .allSatisfy { name ->
                assertThat(name).endsWith("-$clusterId")
                assertThat(name.length).isLessThanOrEqualTo(Constants.K8s.DNS_SUBDOMAIN_MAX_LENGTH)
            }
    }

    private fun clusterScopedObjects(scope: InstallScope): List<HasMetadata> {
        val otel = OtelManifestBuilder(templateService)
        val hostCollector = OtelHostCollectorManifestBuilder(templateService)
        val pyroscope = PyroscopeManifestBuilder(templateService)
        val kubeStateMetrics = KubeStateMetricsManifestBuilder()
        return listOf(
            otel.buildClusterRole(scope),
            otel.buildClusterRoleBinding(scope),
            hostCollector.buildClusterRole(scope),
            hostCollector.buildClusterRoleBinding(scope),
            pyroscope.buildEbpfClusterRole(scope),
            pyroscope.buildEbpfClusterRoleBinding(scope),
            kubeStateMetrics.buildClusterRole(scope),
            kubeStateMetrics.buildClusterRoleBinding(scope),
        )
    }

    private companion object {
        const val UUID_LENGTH = 36
        const val EXPECTED_OBJECTS = 8
    }
}
