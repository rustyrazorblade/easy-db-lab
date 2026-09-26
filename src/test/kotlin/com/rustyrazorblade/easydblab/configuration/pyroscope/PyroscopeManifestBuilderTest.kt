package com.rustyrazorblade.easydblab.configuration.pyroscope

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.YamlTestSupport.scalarAt
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
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
    private lateinit var builder: PyroscopeManifestBuilder
    private lateinit var templateService: TemplateService
    private lateinit var mockClusterStateManager: ClusterStateManager

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
                versions = mutableMapOf(),
                hosts = mutableMapOf(),
                s3Bucket = "acct-bucket",
                dataBucket = "easy-db-lab-data-abc",
                initConfig = InitConfig(tenant = "acme"),
            ),
        )
        templateService = getKoin().get()
        builder = PyroscopeManifestBuilder(templateService)
    }

    @Test
    fun `buildServerDeployment has no init container`() {
        val deployment = builder.buildServerDeployment()

        assertThat(deployment.spec.template.spec.initContainers).isNullOrEmpty()
    }

    @Test
    fun `the server's startup budget outlasts Pyroscope 2's fixed readiness waits`() {
        val container =
            builder
                .buildServerDeployment()
                .spec.template.spec.containers
                .single()
        val startup = container.startupProbe

        assertThat(startup).isNotNull
        assertThat(startup.httpGet.path).isEqualTo("/ready")
        assertThat(startup.httpGet.port.intVal).isEqualTo(PyroscopeManifestBuilder.SERVER_PORT)
        // Pyroscope 2.3.1 answers /ready with 503 for the metastore's 15 s min-ready wait and then
        // the segment writer's 30 s one, back to back: 48 s measured on an idle machine. The
        // kubelet must not kill it inside that window, and a busy control node is slower.
        val budgetSeconds = (startup.initialDelaySeconds ?: 0) + startup.periodSeconds * startup.failureThreshold
        assertThat(budgetSeconds).isGreaterThanOrEqualTo(3 * 48)
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

    private fun serverConfig(): String = builder.buildServerConfigMap().data.getValue("config.yaml")

    @Test
    fun `the server runs native multi-tenancy on pure v2 storage`() {
        val config = serverConfig()

        assertThat(scalarAt(config, "multitenancy_enabled")).isEqualTo("true")
        assertThat(scalarAt(config, "architecture_storage")).isEqualTo("v2")
    }

    @Test
    fun `nothing is ever deleted by age`() {
        val config = serverConfig()

        assertThat(scalarAt(config, "metastore", "index", "cleanup_interval")).isEqualTo("0s")
        assertThat(scalarAt(config, "limits", "retention_period")).isEqualTo("0s")
    }

    @Test
    fun `profiles are stored under the observability profiles prefix of the account bucket, not the data bucket`() {
        val config = serverConfig()

        assertThat(scalarAt(config, "storage", "backend")).isEqualTo("s3")
        assertThat(scalarAt(config, "storage", "s3", "bucket_name")).isEqualTo("acct-bucket")
        assertThat(scalarAt(config, "storage", "prefix")).isEqualTo("observability/profiles")
        assertThat(config).doesNotContain("easy-db-lab-data-abc")
    }

    @Test
    fun `the metastore index lives on the control node's disk`() {
        val config = serverConfig()
        val pod =
            builder
                .buildServerDeployment()
                .spec.template.spec
        val volume = pod.volumes.single { it.name == "data" }
        val mount = pod.containers[0].volumeMounts.single { it.name == "data" }

        assertThat(volume.hostPath.path).isEqualTo(PyroscopeManifestBuilder.DATA_HOST_PATH)
        assertThat(scalarAt(config, "metastore", "data_dir")).startsWith(mount.mountPath + "/")
        assertThat(scalarAt(config, "metastore", "raft", "dir")).startsWith(mount.mountPath + "/")
        assertThat(scalarAt(config, "metastore", "raft", "snapshots_dir")).startsWith(mount.mountPath + "/")
    }

    @Test
    fun `the eBPF agent sends the cluster's tenant on every write`() {
        val alloy = builder.buildEbpfConfigMap().data.getValue("config.alloy")
        val env =
            builder
                .buildEbpfDaemonSet()
                .spec.template.spec.containers[0]
                .env

        assertThat(alloy).contains("headers = {")
        assertThat(alloy).contains("\"X-Scope-OrgID\" = sys.env(\"TENANT\")")
        val tenant = env.single { it.name == "TENANT" }.valueFrom.configMapKeyRef
        assertThat(tenant.name).isEqualTo("cluster-config")
        assertThat(tenant.key).isEqualTo("tenant")
    }
}
