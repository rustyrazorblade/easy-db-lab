package com.rustyrazorblade.easydblab.profiling

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.sidecar.SidecarManifestBuilder
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Proves that every Pyroscope profile producer resolves to the external stack when the cluster is
 * in telemetry-redirect mode, rather than to the in-cluster server on the control node.
 *
 * The control node is deliberately given a concrete private IP so the local-mode Pyroscope URL is a
 * distinct, assertable string: a redirect must displace it, not merely add the external URL beside
 * it. Uses real TemplateService and real manifest builders (never mock configuration classes).
 */
class ProfilingRedirectTest : BaseKoinTest() {
    private val redirect = TelemetryRedirect.fromBaseHost("10.0.0.9")

    /** The Pyroscope URL local mode would have produced for this control node. */
    private val localPyroscopeUrl = "http://$CONTROL_NODE_IP:4040"

    private lateinit var templateService: TemplateService
    private lateinit var mockClusterStateManager: ClusterStateManager

    private val controlHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = CONTROL_NODE_IP,
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager>().also { mockClusterStateManager = it } }
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
                hosts = mutableMapOf(ServerType.Control to listOf(controlHost)),
            ),
        )
        templateService = getKoin().get()
    }

    @Test
    fun `pyroscopeIngestBaseUrl resolves to the external profiles endpoint under redirect`() {
        val url = pyroscopeIngestBaseUrl(CONTROL_NODE_IP, redirect)

        assertThat(url).isEqualTo(redirect.profiles)
        assertThat(url).doesNotContain(CONTROL_NODE_IP)
    }

    @Test
    fun `eBPF DaemonSet points the write URL at the external stack under redirect`() {
        val writeUrl = ebpfWriteUrl(redirect)

        assertThat(writeUrl).isEqualTo(redirect.profiles)
        assertThat(writeUrl).doesNotContain(CONTROL_NODE_IP)
    }

    @Test
    fun `eBPF DaemonSet points the write URL at the control node in local mode`() {
        val writeUrl = ebpfWriteUrl(telemetryRedirect = null)

        assertThat(writeUrl).isEqualTo(localPyroscopeUrl)
    }

    @Test
    fun `eBPF DaemonSet stamps the cluster name from state, not a literal placeholder`() {
        val builder = PyroscopeManifestBuilder(templateService)
        val alloy =
            builder
                .buildEbpfDaemonSet(redirect)
                .spec.template.spec.containers
                .single { it.name == "alloy" }
        val clusterName = alloy.env.single { it.name == "CLUSTER_NAME" }.value

        // The write URL and cluster are read at runtime via env vars, so the state's cluster name
        // must actually flow into the DaemonSet rather than being left as the "__CLUSTER_NAME__" token.
        assertThat(clusterName).startsWith("test-cluster")
        assertThat(clusterName).doesNotContain("__CLUSTER_NAME__")
    }

    @Test
    fun `eBPF config keeps the kubernetes meta relabel labels intact`() {
        val builder = PyroscopeManifestBuilder(templateService)
        val configMap =
            builder
                .buildAgentResources(redirect)
                .filterIsInstance<ConfigMap>()
                .single { it.metadata.name == "pyroscope-ebpf-config" }
        val alloy = configMap.data.getValue("config.alloy")

        // Regression guard for the delimiter-collision fix: the write URL and cluster are now read
        // via sys.env(...), which is what lets the Alloy "__meta_kubernetes_*" relabel labels survive
        // template rendering untouched. If someone reintroduces "__KEY__" substitution here, these
        // labels get mangled and pod attribution silently breaks.
        assertThat(alloy).contains(
            "__meta_kubernetes_pod_phase",
            "__meta_kubernetes_namespace",
            "__meta_kubernetes_pod_name",
            "__meta_kubernetes_pod_container_name",
        )
        assertThat(alloy).contains("sys.env(\"PYROSCOPE_WRITE_URL\")")
        assertThat(alloy).contains("sys.env(\"CLUSTER_NAME\")")
    }

    /** The `PYROSCOPE_WRITE_URL` env value the eBPF Alloy container is launched with. */
    private fun ebpfWriteUrl(telemetryRedirect: TelemetryRedirect?): String {
        val builder = PyroscopeManifestBuilder(templateService)
        val alloy =
            builder
                .buildEbpfDaemonSet(telemetryRedirect)
                .spec.template.spec.containers
                .single { it.name == "alloy" }
        return alloy.env.single { it.name == "PYROSCOPE_WRITE_URL" }.value
    }

    @Test
    fun `sidecar JAVA_TOOL_OPTIONS points the Pyroscope agent at the external stack under redirect`() {
        val builder = SidecarManifestBuilder(templateService)

        val daemonSet =
            builder
                .buildAllResources(
                    controlNodeIp = CONTROL_NODE_IP,
                    clusterName = "test-cluster",
                    telemetryRedirect = redirect,
                ).filterIsInstance<DaemonSet>()
                .single()

        val sidecar =
            daemonSet.spec.template.spec.containers
                .single { it.name == "cassandra-sidecar" }
        val javaToolOptions = sidecar.env.single { it.name == "JAVA_TOOL_OPTIONS" }.value

        assertThat(javaToolOptions).contains(redirect.profiles)
        assertThat(javaToolOptions).doesNotContain(localPyroscopeUrl)
    }

    companion object {
        private const val CONTROL_NODE_IP = "10.0.0.55"
    }
}
