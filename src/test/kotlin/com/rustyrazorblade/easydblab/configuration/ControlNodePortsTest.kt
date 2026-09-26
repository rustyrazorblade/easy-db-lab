package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.YamlTestSupport.scalarAt
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
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
 * Every workload that shares the control node's network must listen on its own ports: two that
 * claim one port crash-loop whichever starts second. Mimir, Loki, Tempo and Pyroscope are all
 * dskit processes with the same default gRPC and gossip ports, so the ports set only in their
 * config files count as much as the declared container ports.
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

    /** A pod on the host network that may be scheduled on the control node: no node selector, or the control plane's. */
    private fun controlNodePods(resources: List<HasMetadata>): Map<String, PodSpec> =
        resources
            .mapNotNull {
                when (it) {
                    is Deployment -> it.metadata.name to it.spec.template.spec
                    is DaemonSet -> it.metadata.name to it.spec.template.spec
                    else -> null
                }
            }.filter { (_, pod) -> pod.hostNetwork == true }
            .filter { (_, pod) ->
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
                OtelManifestBuilder(templates).buildAllResources() +
                JournaldOtelManifestBuilder(templates).buildAllResources() +
                EbpfExporterManifestBuilder().buildAllResources() +
                BeylaManifestBuilder(templates).buildAllResources() +
                PyroscopeManifestBuilder(templates).buildAllResources() +
                GrafanaManifestBuilder(templates).buildAllResources() +
                RegistryManifestBuilder().buildAllResources() +
                S3ManagerManifestBuilder(templates).buildAllResources() +
                YaceManifestBuilder(templates).buildAllResources()

        val declared =
            controlNodePods(resources).mapValues { (_, pod) ->
                pod.containers
                    .flatMap { it.ports.orEmpty() }
                    .map { it.containerPort }
                    .toSet()
            }
        val ports =
            declared.toMutableMap().apply {
                merge("mimir", configuredPorts(mimir, MimirManifestBuilder.CONFIG_FILE), Set<Int>::plus)
                merge("loki", configuredPorts(loki, LokiManifestBuilder.CONFIG_FILE), Set<Int>::plus)
                merge("tempo", configuredPorts(tempo, "tempo.yaml"), Set<Int>::plus)
                merge("pyroscope", PYROSCOPE_IMPLICIT_PORTS, Set<Int>::plus)
            }
        assertThat(ports.keys).contains("mimir", "loki", "tempo", "pyroscope", "otel-collector")

        val claims = ports.flatMap { (workload, set) -> set.map { it to workload } }.groupBy({ it.first }, { it.second })
        assertThat(claims.filterValues { it.size > 1 }).isEmpty()
    }
}
