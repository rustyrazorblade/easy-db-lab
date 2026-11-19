package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressStart
import com.rustyrazorblade.easydblab.configuration.CapabilityProbePods
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
import com.rustyrazorblade.easydblab.configuration.otel.OtelHostCollectorManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.sidecar.SidecarManifestBuilder
import com.rustyrazorblade.easydblab.configuration.store.S3Backend
import com.rustyrazorblade.easydblab.configuration.stress.StressCluster
import com.rustyrazorblade.easydblab.configuration.stress.StressJobManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import com.rustyrazorblade.easydblab.services.StressJobConfig
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.utils.Serialization
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

/**
 * Node tuning widens the ephemeral port range to 1024-65535, so every port a host-network
 * listener binds must be in the packaged `ip_local_reserved_ports`: otherwise an outbound
 * connection can take it as its source port, and the listener fails to bind on its next restart.
 *
 * The ports come from the rendered manifests of every workload on the host network (declared
 * container and host ports, the ports their `*PORT` environment variables name, and the listen
 * addresses and ports in their config) and from the host services that no manifest names. A new fixed host port fails this test until it is
 * reserved.
 */
class NodeTuningReservedPortsTest : BaseKoinTest() {
    private companion object {
        /** The lowest port the tuned ephemeral range hands out. */
        const val EPHEMERAL_MIN = 1024

        /** The UDP port flannel's VXLAN backend binds, the K3s default when the CNI is flannel. */
        const val FLANNEL_VXLAN_PORT = 8472

        /** The highest TCP or UDP port, the top of the tuned ephemeral range. */
        const val MAX_PORT = 65535

        /**
         * A port a config binds: `*_port: N`, `port: N` or a camelCase `httpPort: N`, a listen
         * address `host:N`, or a bare `:N` listen address after `=` or a quote (`-listen=:9400`,
         * `":8080"`).
         */
        val CONFIG_PORT =
            Regex(
                """(?:\b(?i:[a-z_]*port)["']?\s*[:=]\s*["']?(\d{2,5})\b)""" +
                    """|(?:\b(?:0\.0\.0\.0|localhost|127\.0\.0\.1|\[::]):(\d{2,5})\b)""" +
                    """|(?:[="']:(\d{2,5})\b)""",
            )

        /** The source tree of the manifest builders, read from the project directory Gradle runs tests in. */
        val CONFIGURATION_SOURCES: Path = Path.of("src/main/kotlin/com/rustyrazorblade/easydblab/configuration")

        /** A builder line that puts a pod on the host network. */
        val HOST_NETWORK_CALL = Regex("""\bhostNetworked\(\)|withHostNetwork\(true\)""")

        /** Where `hostNetworked()` itself is defined, which no workload is built from. */
        const val HOST_NETWORK_HELPER = "PodPlacement.kt"
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

    /** The port a [CONFIG_PORT] match captured, from whichever alternative matched. */
    private fun portIn(match: MatchResult): Int =
        match.groupValues
            .drop(1)
            .first { it.isNotEmpty() }
            .toInt()

    /** The packaged reserved list, as written after `=`. */
    private fun reservedList(): String =
        javaClass
            .getResource("${Constants.NodeTuning.RESOURCE_DIR}/${Constants.NodeTuning.SYSCTL_FILE}")!!
            .readText()
            .lines()
            .single { it.startsWith("net.ipv4.ip_local_reserved_ports") }
            .substringAfter("=")
            .trim()

    /** [ports] sorted, with adjacent ports merged into ranges: the form the kernel reads back. */
    private fun canonical(ports: Set<Int>): String {
        val ranges = mutableListOf<IntRange>()
        ports.sorted().forEach { port ->
            val last = ranges.lastOrNull()
            if (last != null && port == last.last + 1) {
                ranges[ranges.lastIndex] = last.first..port
            } else {
                ranges += port..port
            }
        }
        return ranges.joinToString(",") { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" }
    }

    /** The packaged reserved list, expanded to single ports. */
    private fun reservedPorts(): Set<Int> =
        reservedList()
            .split(",")
            .flatMap { part ->
                val bounds = part.split("-").map { it.trim().toInt() }
                (bounds.first()..bounds.last()).toList()
            }.toSet()

    /** Every workload builder's resources, keyed by the builder's class name. */
    private fun workloads(): Map<String, List<HasMetadata>> {
        val templates: TemplateService = getKoin().get()
        val compactorJar = CompactorAgentJar("0".repeat(64))
        val sidecar = SidecarManifestBuilder(templates).buildAllResources("sidecar:test", "test-1", "tenant")
        return mapOf(
            builder<LokiManifestBuilder>() to LokiManifestBuilder(templates).buildAllResources(),
            builder<MimirManifestBuilder>() to MimirManifestBuilder(templates).buildAllResources(),
            builder<TempoManifestBuilder>() to TempoManifestBuilder(templates).buildAllResources(),
            builder<GrafanaManifestBuilder>() to
                GrafanaManifestBuilder(templates).buildAllResources(DocumentsBucket("acct", "us-west-2"), ObservabilityHosting.AWS),
            builder<PyroscopeManifestBuilder>() to PyroscopeManifestBuilder(templates).buildAllResources(),
            builder<RegistryManifestBuilder>() to RegistryManifestBuilder().buildAllResources(),
            builder<S3ManagerManifestBuilder>() to S3ManagerManifestBuilder(templates).buildAllResources(),
            builder<YaceManifestBuilder>() to YaceManifestBuilder(templates).buildAllResources(),
            builder<KubeStateMetricsManifestBuilder>() to KubeStateMetricsManifestBuilder().buildAllResources(),
            builder<CompactorManifestBuilder>() to
                CompactorManifestBuilder(templates).buildAllResources(compactorJar, S3Backend(bucket = "acct", region = "eu-west-1")),
            builder<OtelManifestBuilder>() to OtelManifestBuilder(templates).buildAllResources(NodeCapabilities.AWS),
            builder<OtelHostCollectorManifestBuilder>() to OtelHostCollectorManifestBuilder(templates).buildAllResources(CniMode.Cilium),
            builder<JournaldOtelManifestBuilder>() to JournaldOtelManifestBuilder(templates).buildAllResources(),
            builder<EbpfExporterManifestBuilder>() to EbpfExporterManifestBuilder().buildAllResources(),
            builder<BeylaManifestBuilder>() to BeylaManifestBuilder(templates).buildAllResources(),
            builder<SidecarManifestBuilder>() to sidecar,
            builder<StressJobManifestBuilder>() to stressJobs(),
            // The capability probes bind no port; scanned so the host-network probe is accounted for.
            "CapabilityProbePods" to CapabilityProbePods.probes("default").values.toList(),
        )
    }

    private inline fun <reified T : Any> builder(): String = T::class.simpleName!!

    /** The builders under configuration/ that put a pod on the host network, by file name. */
    private fun hostNetworkBuilders(): Set<String> =
        Files.walk(CONFIGURATION_SOURCES).use { paths ->
            paths
                .filter { it.toString().endsWith(".kt") && it.fileName.toString() != HOST_NETWORK_HELPER }
                .filter { HOST_NETWORK_CALL.containsMatchIn(Files.readString(it)) }
                .map { it.fileName.toString().removeSuffix(".kt") }
                .toList()
                .toSet()
        }

    /** The stress Job and the command Job, both on the host network of an app node. */
    private fun stressJobs(): List<HasMetadata> {
        val image = "ghcr.io/apache/cassandra-easy-stress:latest"
        val config =
            StressJobConfig(
                jobName = "stress-test",
                image = image,
                args = listOf("run", "KeyValue"),
                contactPoints = "10.0.0.2",
            )
        val cluster = StressCluster("us-west-2", "test", "tenant", telemetryRedirect = null)
        return listOf(
            StressJobManifestBuilder.buildStressJob(config, cluster),
            StressJobManifestBuilder.buildCommandJob("stress-command-test", image, listOf("list")),
        )
    }

    private fun podSpecs(resources: List<HasMetadata>): List<PodSpec> =
        resources.mapNotNull {
            when (it) {
                is Deployment -> it.spec.template.spec
                is DaemonSet -> it.spec.template.spec
                is Job -> it.spec.template.spec
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
                podSpecs(resources)
                    .filter { it.hostNetwork == true }
                    .flatMap { it.containers + it.initContainers.orEmpty() }
                    .flatMap { it.env.orEmpty() }
                    .filter { it.name.endsWith("PORT") }
                    .mapNotNull { it.value?.toIntOrNull() }
                    .forEach { ports.putIfAbsent(it, name) }
                val rendered = resources.joinToString("\n") { Serialization.asYaml(it) }
                CONFIG_PORT
                    .findAll(rendered)
                    .map { match -> portIn(match) }
                    .forEach { ports.putIfAbsent(it, name) }
            }
        // The ephemeral range runs from 1024 to the top of the port space; the NodePort range inside
        // it is reserved whole, and checked by its own test.
        return ports.filterKeys {
            it in EPHEMERAL_MIN..MAX_PORT && it !in Constants.NodeTuning.NODE_PORT_MIN..Constants.NodeTuning.NODE_PORT_MAX
        }
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
        assertThat(ports.keys).contains(
            Constants.K8s.PYROSCOPE_PORT,
            Constants.K8s.OTEL_GRPC_PORT,
            Constants.Yace.PORT,
            Constants.Stress.PROMETHEUS_PORT,
        )

        val missing = ports.filterKeys { it !in reservedPorts() }
        assertThat(missing).describedAs("host-network ports missing from ip_local_reserved_ports, with the workload").isEmpty()
    }

    @Test
    fun `every metrics port a stress run can take is reserved`() {
        val window = (1..Constants.Stress.PROMETHEUS_PORT_COUNT).map { StressStart.promPortFor(it) }
        assertThat(reservedPorts()).containsAll(window)
    }

    @Test
    fun `every host service port is reserved`() {
        assertThat(reservedPorts()).containsAll(Constants.NodeTuning.HOST_SERVICE_PORTS)
    }

    // The flannel CNI's kernel VXLAN socket binds its UDP port when flannel.1 comes up. No manifest
    // builder names it, so the host service list must, or an outbound socket can take it first.
    @Test
    fun `the flannel VXLAN port is a reserved host service port`() {
        assertThat(Constants.NodeTuning.HOST_SERVICE_PORTS).contains(FLANNEL_VXLAN_PORT)
        assertThat(Constants.NodeTuning.FLANNEL_VXLAN_PORT).isEqualTo(FLANNEL_VXLAN_PORT)
    }

    // verify compares the kernel's read-back string with the packaged one exactly, so the packaged
    // list must already be sorted and merged.
    @Test
    fun `the packaged reserved list is in the form the kernel reads back`() {
        assertThat(reservedList()).isEqualTo(canonical(reservedPorts()))
    }

    @Test
    fun `the canonical form sorts and merges adjacent ports`() {
        assertThat(canonical(setOf(9043, 7000, 9042, 7001, 7199, 9044))).isEqualTo("7000-7001,7199,9042-9044")
    }

    @Test
    fun `bare listen addresses are scanned for ports`() {
        val ports =
            CONFIG_PORT
                .findAll("args: [\"-listen=:9400\"]\naddr: \":8080\"\nlisten: '0.0.0.0:3100'\nhttpPort: 3200\nlistenPort=9401")
                .map { match -> portIn(match) }
                .toList()
        assertThat(ports).containsExactly(9400, 8080, 3100, 3200, 9401)
    }

    @Test
    fun `every host-network builder is scanned`() {
        val builders = hostNetworkBuilders()
        // The source walk must find the builders; an empty result would pass vacuously.
        assertThat(builders).contains(builder<PyroscopeManifestBuilder>(), builder<StressJobManifestBuilder>())
        assertThat(workloads().keys).containsAll(builders)
    }
}
