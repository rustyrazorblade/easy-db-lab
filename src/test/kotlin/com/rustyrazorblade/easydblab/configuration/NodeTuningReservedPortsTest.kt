package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorAgentJar
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.DocumentsBucket
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.kubestatemetrics.KubeStateMetricsManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.JournaldOtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.sidecar.SidecarManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.utils.Serialization
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Node tuning widens the ephemeral port range to 1024-65535, so every port a host-network
 * listener binds must be in the packaged `ip_local_reserved_ports`: otherwise an outbound
 * connection can take it as its source port, and the listener fails to bind on its next restart.
 *
 * The ports come from the rendered manifests of every workload on the host network (declared
 * container and host ports, and the listen addresses and ports in their config) and from the
 * host services that no manifest names. A new fixed host port fails this test until it is
 * reserved.
 */
class NodeTuningReservedPortsTest : BaseKoinTest() {
    private companion object {
        /** The lowest port the tuned ephemeral range hands out. */
        const val EPHEMERAL_MIN = 1024

        /**
         * Host listeners no manifest builder names: K3s, its embedded etcd and containerd, the
         * kubelet and kube-proxy, the Cilium agent and Hubble, Pyroscope's built-in Raft default,
         * and the database on db nodes. Read from `ss -tlnp` on a running cluster's nodes.
         */
        val HOST_SERVICE_PORTS =
            setOf(
                // K3s API server and supervisor, embedded etcd client, peer, metrics and
                // loopback client, and containerd's CRI stream server
                6443,
                6444,
                2379,
                2380,
                Constants.K3s.ETCD_METRICS_PORT,
                2382,
                10010,
                // Pyroscope's metastore Raft listener (its default, not in its config file)
                9099,
                // kubelet, kube-proxy, controller manager, scheduler, kubelet read-only
                10248,
                10249,
                10250,
                10251,
                10252,
                10255,
                10256,
                10257,
                10259,
                // Cilium health, Hubble server and relay, agent pprof and metrics, operator, Hubble metrics
                4240,
                4244,
                4245,
                9234,
                9878,
                9879,
                9890,
                9891,
                Constants.Cilium.AGENT_METRICS_PORT,
                Constants.Cilium.OPERATOR_METRICS_PORT,
                Constants.Cilium.HUBBLE_METRICS_PORT,
                // Cassandra storage, TLS storage, JMX, native and TLS native
                7000,
                7001,
                7199,
                Constants.Stress.DEFAULT_CASSANDRA_PORT,
                9142,
                // The stress server's metrics endpoint on app nodes
                Constants.Stress.PROMETHEUS_PORT,
            )

        /** A port a config binds: `*_port: N`, `port: N`, or a listen address `host:N`. */
        val CONFIG_PORT =
            Regex(
                """(?:\b[a-z_]*port["']?\s*[:=]\s*["']?(\d{2,5})\b)|(?:\b(?:0\.0\.0\.0|localhost|127\.0\.0\.1|\[::]):(\d{2,5})\b)""",
            )
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

    /** The packaged reserved list, expanded to single ports. */
    private fun reservedPorts(): Set<Int> {
        val line =
            javaClass
                .getResource("${Constants.NodeTuning.RESOURCE_DIR}/${Constants.NodeTuning.SYSCTL_FILE}")!!
                .readText()
                .lines()
                .single { it.startsWith("net.ipv4.ip_local_reserved_ports") }
        return line
            .substringAfter("=")
            .trim()
            .split(",")
            .flatMap { part ->
                val bounds = part.split("-").map { it.trim().toInt() }
                (bounds.first()..bounds.last()).toList()
            }.toSet()
    }

    private fun workloads(): Map<String, List<HasMetadata>> {
        val templates: TemplateService = getKoin().get()
        return mapOf(
            "loki" to LokiManifestBuilder(templates).buildAllResources(),
            "mimir" to MimirManifestBuilder(templates).buildAllResources(),
            "tempo" to TempoManifestBuilder(templates).buildAllResources(),
            "grafana" to GrafanaManifestBuilder(templates).buildAllResources(DocumentsBucket("acct", "us-west-2")),
            "pyroscope" to PyroscopeManifestBuilder(templates).buildAllResources(),
            "registry" to RegistryManifestBuilder().buildAllResources(),
            "s3manager" to S3ManagerManifestBuilder(templates).buildAllResources(),
            "yace" to YaceManifestBuilder(templates).buildAllResources(),
            "kube-state-metrics" to KubeStateMetricsManifestBuilder().buildAllResources(),
            "compactor" to CompactorManifestBuilder(templates).buildAllResources(CompactorAgentJar("0".repeat(64)), "eu-west-1"),
            "otel" to OtelManifestBuilder(templates).buildAllResources(),
            "journald-otel" to JournaldOtelManifestBuilder(templates).buildAllResources(),
            "ebpf-exporter" to EbpfExporterManifestBuilder().buildAllResources(),
            "beyla" to BeylaManifestBuilder(templates).buildAllResources(),
            "sidecar" to SidecarManifestBuilder(templates).buildAllResources("sidecar:test", "10.0.0.1", "test-1", "tenant"),
        )
    }

    private fun podSpecs(resources: List<HasMetadata>): List<PodSpec> =
        resources.mapNotNull {
            when (it) {
                is Deployment -> it.spec.template.spec
                is DaemonSet -> it.spec.template.spec
                else -> null
            }
        }

    /** Every port a host-network workload declares or configures, below the NodePort range. */
    private fun hostNetworkPorts(): Map<Int, String> {
        val ports = mutableMapOf<Int, String>()
        workloads()
            .filterValues { resources -> podSpecs(resources).any { it.hostNetwork == true } }
            .forEach { (name, resources) ->
                podSpecs(resources)
                    .filter { it.hostNetwork == true }
                    .flatMap { it.containers + it.initContainers.orEmpty() }
                    .flatMap { it.ports.orEmpty() }
                    .flatMap { listOfNotNull(it.containerPort, it.hostPort) }
                    .forEach { ports.putIfAbsent(it, name) }
                val rendered = resources.joinToString("\n") { Serialization.asYaml(it) }
                CONFIG_PORT
                    .findAll(rendered)
                    .map { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toInt() }
                    .forEach { ports.putIfAbsent(it, name) }
            }
        return ports.filterKeys { it in EPHEMERAL_MIN until Constants.NodeTuning.NODE_PORT_MIN }
    }

    @Test
    fun `the whole NodePort range is reserved`() {
        assertThat(reservedPorts())
            .containsAll((Constants.NodeTuning.NODE_PORT_MIN..Constants.NodeTuning.NODE_PORT_MAX).toList())
    }

    @Test
    fun `every port a host-network workload binds is reserved`() {
        val ports = hostNetworkPorts()
        // The scan must see the backends; an empty result would pass vacuously.
        assertThat(ports.keys).contains(Constants.K8s.MIMIR_HTTP_PORT, Constants.K8s.OTEL_GRPC_PORT, Constants.Yace.PORT)

        val missing = ports.filterKeys { it !in reservedPorts() }
        assertThat(missing).describedAs("host-network ports missing from ip_local_reserved_ports, with the workload").isEmpty()
    }

    @Test
    fun `every host service port is reserved`() {
        assertThat(reservedPorts()).containsAll(HOST_SERVICE_PORTS)
    }
}
