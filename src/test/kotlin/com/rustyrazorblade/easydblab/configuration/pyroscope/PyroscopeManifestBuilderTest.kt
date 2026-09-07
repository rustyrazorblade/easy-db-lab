package com.rustyrazorblade.easydblab.configuration.pyroscope

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.aws.AccountBucketRegionService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for PyroscopeManifestBuilder.
 *
 * Uses real TemplateService (never mocked per project convention) to verify
 * config file loading from classpath resources.
 */
class PyroscopeManifestBuilderTest : BaseKoinTest() {
    private val dataBucket = "easy-db-lab-data-test-id"

    private lateinit var builder: PyroscopeManifestBuilder
    private lateinit var templateService: TemplateService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockAccountBucketRegionService: AccountBucketRegionService

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        mockClusterStateManager = getKoin().get()
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                clusterId = "test-id",
                versions = mutableMapOf(),
                hosts = mutableMapOf(),
                s3Bucket = "easy-db-lab-account",
                dataBucket = dataBucket,
                initConfig = InitConfig(region = "us-east-2", name = "test-cluster"),
            ),
        )
        mockAccountBucketRegionService = mock()
        whenever(mockAccountBucketRegionService.resolve()).thenReturn("eu-west-1")
        templateService = getKoin().get()
        builder = PyroscopeManifestBuilder(templateService, mockAccountBucketRegionService)
    }

    @Test
    fun `the server config stores profiles in the account bucket, not the data bucket`() {
        // `down` expires the per-cluster data bucket wholesale, so a profile stored there does not
        // stay retrievable. The account bucket accumulates and carries no expiry.
        val config = builder.buildServerConfigMap().data.getValue("config.yaml")

        assertThat(config).contains("bucket_name: easy-db-lab-account")
        assertThat(config).doesNotContain(dataBucket)
    }

    @Test
    fun `the server config takes its endpoint and region from the bucket, not the cluster`() {
        // The cluster is in us-east-2 and the account bucket is in eu-west-1. A config built from
        // the cluster's region points at the wrong endpoint.
        val config = builder.buildServerConfigMap().data.getValue("config.yaml")

        assertThat(config).contains("endpoint: s3.eu-west-1.amazonaws.com")
        assertThat(config).contains("region: eu-west-1")
        assertThat(config).doesNotContain("us-east-2")
    }

    @Test
    fun `a blank region fails the build rather than rendering an endpoint with a hole in it`() {
        // GetBucketLocation reports us-east-1 as no location constraint at all. If that reaches the
        // template untranslated the config reads `endpoint: s3..amazonaws.com`, which resolves to
        // nothing — and Pyroscope only finds out on its first profile write.
        whenever(mockAccountBucketRegionService.resolve()).thenReturn("")

        assertThatThrownBy { builder.buildServerConfigMap() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("s3..amazonaws.com")
    }

    @Test
    fun `the server config stores profiles inside the cluster prefix`() {
        val config = builder.buildServerConfigMap().data.getValue("config.yaml")

        assertThat(config).contains("prefix: clusters/test-cluster-test-id/pyroscope")
    }

    @Test
    fun `buildServerDeployment has no init container`() {
        val deployment = builder.buildServerDeployment()

        assertThat(deployment.spec.template.spec.initContainers).isNullOrEmpty()
    }

    @Test
    fun `eBPF config discovers Kubernetes pods and derives per-pod attribution labels`() {
        val configMap = builder.buildEbpfConfigMap()
        val config = configMap.data.getValue("config.alloy")

        // Discovers pods on the local node so processes can be attributed.
        assertThat(config).contains("discovery.kubernetes \"pods\"")
        assertThat(config).contains("spec.nodeName=")
        // Joins host processes to pod metadata by container id.
        assertThat(config).contains("join")
        assertThat(config).contains("discovery.relabel.kubernetes_pods.output")
        // Produces the dimensions Pyroscope filters on.
        assertThat(config).contains("target_label  = \"service_name\"")
        assertThat(config).contains("target_label  = \"pod\"")
        assertThat(config).contains("target_label  = \"container\"")
        assertThat(config).contains("__meta_kubernetes_pod_container_name")
    }

    @Test
    fun `eBPF DaemonSet uses the pod-reading ServiceAccount`() {
        val daemonSet = builder.buildEbpfDaemonSet()

        assertThat(daemonSet.spec.template.spec.serviceAccountName).isEqualTo("pyroscope-ebpf")
    }

    @Test
    fun `eBPF ClusterRole grants read access to pods for discovery`() {
        val clusterRole = builder.buildEbpfClusterRole()

        val podRule =
            clusterRole.rules.single { rule ->
                rule.resources.contains("pods")
            }
        assertThat(podRule.verbs).contains("get", "list", "watch")
    }
}
