package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.YamlTestSupport.scalarAt
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.DocumentsBucket
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.JournaldOtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelHostCollectorManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Every port a workload holds on the control node must be its own: two that claim one port
 * crash-loop whichever starts second. A pod on the host network holds every port it listens on,
 * including the ports set only in its config file (Mimir, Loki, Tempo and Pyroscope are dskit
 * processes with the same default gRPC and gossip ports). A pod on the pod network holds only the
 * container ports it publishes with a `hostPort`. The observability backends and Grafana publish none;
 * their Services hold NodePorts instead, which kube-proxy opens on every node, the control node included.
 */
class ControlNodePortsTest : BaseKoinTest() {
    private companion object {
        /** Ports the Pyroscope server opens without its config naming them: dskit's gRPC and gossip defaults. */
        val PYROSCOPE_IMPLICIT_PORTS = setOf(9095, 7946)

        /** The port every OTel collector serves its own Prometheus telemetry on. */
        const val COLLECTOR_SELF_TELEMETRY_PORT = 8888
    }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "test", s3Bucket = "acct"))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    /** A pod that may be scheduled on the control node: no node selector, or the control plane's. */
    private fun controlNodePods(resources: List<HasMetadata>): Map<String, PodSpec> =
        resources
            .mapNotNull {
                when (it) {
                    is Deployment -> it.metadata.name to it.spec.template.spec
                    is DaemonSet -> it.metadata.name to it.spec.template.spec
                    else -> null
                }
            }.filter { (_, pod) ->
                pod.nodeSelector.isNullOrEmpty() || pod.nodeSelector.containsKey("node-role.kubernetes.io/control-plane")
            }.toMap()

    private fun configuredPorts(
        resources: List<HasMetadata>,
        key: String,
    ): Set<Int> {
        val config =
            resources
                .filterIsInstance<ConfigMap>()
                .firstNotNullOf { it.data?.get(key) }
        return listOfNotNull(
            scalarAt(config, "server", "http_listen_port"),
            scalarAt(config, "server", "grpc_listen_port"),
            scalarAt(config, "memberlist", "bind_port"),
        ).map { it.toInt() }.toSet()
    }

    /** The node ports each Service holds on every node, keyed by the Service's name. */
    private fun nodePorts(resources: List<HasMetadata>): Map<String, Set<Int>> =
        resources
            .filterIsInstance<Service>()
            .associate { service ->
                service.metadata.name to
                    service.spec.ports
                        .mapNotNull { it.nodePort }
                        .toSet()
            }.filterValues { it.isNotEmpty() }

    @Test
    fun `no two workloads on the control node's network share a port`() {
        val templates: TemplateService = getKoin().get()
        val mimir = MimirManifestBuilder(templates).buildAllResources(TestScopes.EC2)
        val loki = LokiManifestBuilder(templates).buildAllResources(TestScopes.EC2)
        val tempo = TempoManifestBuilder(templates).buildAllResources(TestScopes.EC2)
        val resources =
            mimir + loki + tempo +
                OtelManifestBuilder(templates).buildAllResources(TestScopes.EC2, NodeCapabilities.AWS) +
                OtelHostCollectorManifestBuilder(templates).buildAllResources(TestScopes.EC2, CniMode.Cilium) +
                JournaldOtelManifestBuilder(templates).buildAllResources(TestScopes.EC2) +
                EbpfExporterManifestBuilder().buildAllResources(TestScopes.EC2) +
                BeylaManifestBuilder(templates).buildAllResources(TestScopes.EC2) +
                PyroscopeManifestBuilder(templates).buildAllResources(TestScopes.EC2) +
                GrafanaManifestBuilder(
                    templates,
                ).buildAllResources(TestScopes.EC2, DocumentsBucket("acct", "us-west-2"), ObservabilityHosting.AWS) +
                RegistryManifestBuilder().buildAllResources(TestScopes.EC2) +
                S3ManagerManifestBuilder(templates).buildAllResources(TestScopes.EC2) +
                YaceManifestBuilder(templates).buildAllResources(TestScopes.EC2)

        val configured =
            mapOf(
                "mimir" to configuredPorts(mimir, MimirManifestBuilder.CONFIG_FILE),
                "loki" to configuredPorts(loki, LokiManifestBuilder.CONFIG_FILE),
                "tempo" to configuredPorts(tempo, "tempo.yaml"),
                "pyroscope" to PYROSCOPE_IMPLICIT_PORTS,
                // The host collector serves its own telemetry on 8888 without declaring a container port.
                Constants.OtelCollector.HOST_COLLECTOR_NAME to setOf(COLLECTOR_SELF_TELEMETRY_PORT),
            )
        val ports =
            controlNodePods(resources)
                .mapValues { (name, pod) ->
                    val declared = pod.containers.flatMap { it.ports.orEmpty() }
                    when (pod.hostNetwork == true) {
                        true -> declared.map { it.containerPort }.toSet() + configured[name].orEmpty()
                        false -> declared.mapNotNull { it.hostPort }.toSet()
                    }
                }.filterValues { it.isNotEmpty() } + nodePorts(resources)
        assertThat(ports.keys).contains("mimir", "loki", "tempo", "pyroscope", "grafana", Constants.OtelCollector.HOST_COLLECTOR_NAME)
        // The backends and Grafana are on the pod network: they hold no host port, only their Services' NodePorts.
        assertThat(
            controlNodePods(resources).filterKeys { it in nodePorts(resources).keys }.values.flatMap { pod ->
                pod.containers.flatMap { it.ports.orEmpty() }.mapNotNull { it.hostPort }
            },
        ).isEmpty()
        // The core collector is on the pod network with no host port, so it claims no port on the node.
        assertThat(ports.keys).doesNotContain(Constants.K8s.OTEL_COLLECTOR_APP_LABEL)

        val claims = ports.flatMap { (workload, set) -> set.map { it to workload } }.groupBy({ it.first }, { it.second })
        assertThat(claims.filterValues { it.size > 1 }).isEmpty()
    }
}
