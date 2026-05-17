package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.WorkloadScrapeConfig
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapList
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.AnyNamespaceOperation
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class OtelSyncServiceTest : BaseKoinTest() {
    private lateinit var mockK8sClientProvider: K8sClientProvider
    private lateinit var mockK8sService: K8sService
    private lateinit var mockOtelManifestBuilder: OtelManifestBuilder
    private lateinit var mockK8sClient: KubernetesClient
    private lateinit var service: OtelSyncService

    private val controlHost =
        ClusterHost(
            publicIp = "1.2.3.4",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<K8sClientProvider>().also { mockK8sClientProvider = it } }
                single { mock<K8sService>().also { mockK8sService = it } }
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "test", versions = mutableMapOf()))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        mockK8sClientProvider = getKoin().get()
        mockK8sService = getKoin().get()
        mockOtelManifestBuilder = OtelManifestBuilder(getKoin().get())
        mockK8sClient = mock()

        val mockConfigMapOps =
            mock<MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>>>()
        val mockAnyNsOps =
            mock<AnyNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>>>()
        val mockFiltered =
            mock<FilterWatchListDeletable<ConfigMap, ConfigMapList, Resource<ConfigMap>>>()
        val emptyConfigMapList = ConfigMapList().also { it.items = mutableListOf() }

        whenever(mockK8sClient.configMaps()).thenReturn(mockConfigMapOps)
        whenever(mockConfigMapOps.inAnyNamespace()).thenReturn(mockAnyNsOps)
        whenever(mockAnyNsOps.withLabel(any<String>(), any<String>())).thenReturn(mockFiltered)
        whenever(mockFiltered.list()).thenReturn(emptyConfigMapList)

        whenever(mockK8sClientProvider.createClient(any())).thenReturn(mockK8sClient)
        whenever(mockK8sService.applyResource(any(), any())).thenReturn(Result.success(Unit))

        service = DefaultOtelSyncService(mockK8sClientProvider, mockK8sService, mockOtelManifestBuilder)
    }

    @Test
    fun `syncConfigMap creates K8s client for the given host`() {
        service.syncConfigMap(controlHost)

        verify(mockK8sClientProvider).createClient(controlHost)
    }

    @Test
    fun `syncConfigMap applies the OTel collector ConfigMap`() {
        service.syncConfigMap(controlHost)

        verify(mockK8sService).applyResource(any(), any())
    }

    @Test
    fun `syncConfigMap applies a ConfigMap resource`() {
        var appliedResource: Any? = null
        whenever(mockK8sService.applyResource(any(), any())).thenAnswer { invocation ->
            appliedResource = invocation.getArgument(1)
            Result.success(Unit)
        }

        service.syncConfigMap(controlHost)

        assertThat(appliedResource).isInstanceOf(ConfigMap::class.java)
    }

    @Test
    fun `syncConfigMap returns failure when applyResource fails`() {
        whenever(mockK8sService.applyResource(any(), any()))
            .thenReturn(Result.failure(RuntimeException("K8s API error")))

        val result = service.syncConfigMap(controlHost)

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `syncConfigMap closes the K8s client after use`() {
        service.syncConfigMap(controlHost)

        verify(mockK8sClient).close()
    }

    @Test
    fun `syncConfigMap with workload scrape configs produces ConfigMap with dynamic jobs`() {
        val scrapeConfigs = listOf(WorkloadScrapeConfig(jobName = "scylladb", port = 9180, path = "/metrics"))
        val builder = OtelManifestBuilder(getKoin().get())

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = configMap.data["otel-collector-config.yaml"]

        assertThat(yaml).isNotNull
        assertThat(yaml).contains("scylladb")
        assertThat(yaml).contains("9180")
    }
}
