package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.BaseKoinTest
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
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.HasMetadata
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
 * Pins the rendered YAML of every workload manifest the control-plane and node-agent builders
 * produce, so a refactor of the shared pod placement and Deployment skeleton cannot change a
 * single byte of what is applied to a cluster.
 *
 * The expected files live in `src/test/resources/golden/manifests/`. Set `UPDATE_GOLDEN=1` to
 * rewrite them after an intended manifest change, then review the diff.
 */
class ManifestGoldenTest : BaseKoinTest() {
    private companion object {
        const val GOLDEN_DIR = "golden/manifests"
        const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(
                            it.load(),
                        ).thenReturn(
                            ClusterState(name = "test", clusterId = "golden-cluster", s3Bucket = "acct"),
                        )
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    private fun render(resources: List<HasMetadata>): String = resources.joinToString("---\n") { Serialization.asYaml(it) }

    private fun manifests(): Map<String, List<HasMetadata>> {
        val templates: TemplateService = getKoin().get()
        return mapOf(
            "loki" to LokiManifestBuilder(templates).buildAllResources(TestScopes.EC2),
            "mimir" to MimirManifestBuilder(templates).buildAllResources(TestScopes.EC2),
            "tempo" to TempoManifestBuilder(templates).buildAllResources(TestScopes.EC2),
            "grafana" to
                GrafanaManifestBuilder(
                    templates,
                ).buildAllResources(TestScopes.EC2, DocumentsBucket("acct", "us-west-2"), ObservabilityHosting.AWS),
            "pyroscope" to PyroscopeManifestBuilder(templates).buildAllResources(TestScopes.EC2),
            "registry" to RegistryManifestBuilder().buildAllResources(TestScopes.EC2),
            "s3manager" to S3ManagerManifestBuilder(templates).buildAllResources(TestScopes.EC2),
            "yace" to YaceManifestBuilder(templates).buildAllResources(TestScopes.EC2),
            "kube-state-metrics" to KubeStateMetricsManifestBuilder().buildAllResources(TestScopes.EC2),
            "compactor" to
                CompactorManifestBuilder(
                    templates,
                ).buildAllResources(TestScopes.EC2, CompactorAgentJar(SHA), S3Backend(bucket = "acct", region = "eu-west-1")),
            "otel" to OtelManifestBuilder(templates).buildAllResources(TestScopes.EC2, NodeCapabilities.AWS),
            "otel-host" to OtelHostCollectorManifestBuilder(templates).buildAllResources(TestScopes.EC2, CniMode.Cilium),
            "journald-otel" to JournaldOtelManifestBuilder(templates).buildAllResources(TestScopes.EC2),
            "ebpf-exporter" to EbpfExporterManifestBuilder().buildAllResources(TestScopes.EC2),
            "beyla" to BeylaManifestBuilder(templates).buildAllResources(TestScopes.EC2),
            "sidecar" to SidecarManifestBuilder(templates).buildAllResources(TestScopes.EC2, "sidecar:test", "test-1", "tenant"),
        )
    }

    @Test
    fun `every workload manifest renders byte-identical to its golden file`() {
        val update = System.getenv("UPDATE_GOLDEN") == "1"
        val actual = manifests().mapValues { (_, resources) -> render(resources) }
        if (update) {
            val dir = Path.of("src/test/resources", GOLDEN_DIR)
            Files.createDirectories(dir)
            actual.forEach { (name, yaml) -> Files.writeString(dir.resolve("$name.yaml"), yaml) }
        }
        if (update) return
        val differing =
            actual.filter { (name, yaml) ->
                val golden = checkNotNull(javaClass.classLoader.getResource("$GOLDEN_DIR/$name.yaml")) { "missing golden file for $name" }
                yaml != golden.readText()
            }
        assertThat(differing.keys).describedAs("manifests that differ from their golden file").isEmpty()
    }
}
