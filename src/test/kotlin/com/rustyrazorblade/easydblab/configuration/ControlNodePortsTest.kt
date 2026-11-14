package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.YamlTestSupport.scalarAt
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.DocumentsBucket
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.JournaldOtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PodSpec
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
 * container ports it publishes with a `hostPort`; on AWS the observability backends and Grafana
 * are such pods.
 */
class ControlNodePortsTest : BaseKoinTest() {
    private companion object {
        /** Ports the Pyroscope server opens without its config naming them: dskit's gRPC and gossip defaults. */
        val PYROSCOPE_IMPLICIT_PORTS = setOf(9095, 7946)
    }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "test", versions = mutableMapOf(), s3Bucket = "acct"))
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

    @Test
    fun `no two workloads on the control node's network share a port`() {
        val templates: TemplateService = getKoin().get()
        val mimir = MimirManifestBuilder(templates).buildAllResources()
        val loki = LokiManifestBuilder(templates).buildAllResources()
        val tempo = TempoManifestBuilder(templates).buildAllResources()
        val resources =
            mimir + loki + tempo +
                OtelManifestBuilder(templates).buildAllResources(NodeCapabilities.AWS) +
                JournaldOtelManifestBuilder(templates).buildAllResources() +
                EbpfExporterManifestBuilder().buildAllResources() +
                BeylaManifestBuilder(templates).buildAllResources() +
                PyroscopeManifestBuilder(templates).buildAllResources() +
                GrafanaManifestBuilder(templates).buildAllResources(DocumentsBucket("acct", "us-west-2"), ObservabilityHosting.AWS) +
                RegistryManifestBuilder().buildAllResources() +
                S3ManagerManifestBuilder(templates).buildAllResources() +
                YaceManifestBuilder(templates).buildAllResources()

        val configured =
            mapOf(
                "mimir" to configuredPorts(mimir, MimirManifestBuilder.CONFIG_FILE),
                "loki" to configuredPorts(loki, LokiManifestBuilder.CONFIG_FILE),
                "tempo" to configuredPorts(tempo, "tempo.yaml"),
                "pyroscope" to PYROSCOPE_IMPLICIT_PORTS,
            )
        val ports =
            controlNodePods(resources)
                .mapValues { (name, pod) ->
                    val declared = pod.containers.flatMap { it.ports.orEmpty() }
                    when (pod.hostNetwork == true) {
                        true -> declared.map { it.containerPort }.toSet() + configured[name].orEmpty()
                        false -> declared.mapNotNull { it.hostPort }.toSet()
                    }
                }.filterValues { it.isNotEmpty() }
        assertThat(ports.keys).contains("mimir", "loki", "tempo", "pyroscope", "grafana", "otel-collector")

        val claims = ports.flatMap { (workload, set) -> set.map { it to workload } }.groupBy({ it.first }, { it.second })
        assertThat(claims.filterValues { it.size > 1 }).isEmpty()
    }
}
