package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.kubestatemetrics.KubeStateMetricsManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.JournaldOtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Every image the observability stack runs is pinned to a released version, never `latest`: a
 * `latest` tag changes what a cluster runs between two `up`s with no change in this repository, and
 * a major release (Tempo 3, Pyroscope 2) changes the configuration keys under it.
 *
 * The images are read from the resources the builders actually produce, not from source text, so a
 * builder that picks up a new image reference is covered without a list to maintain.
 */
class ObservabilityImagePinsTest : BaseKoinTest() {
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

    private fun podSpecs(resources: List<HasMetadata>): List<PodSpec> =
        resources.mapNotNull {
            when (it) {
                is Deployment -> it.spec.template.spec
                is DaemonSet -> it.spec.template.spec
                is StatefulSet -> it.spec.template.spec
                else -> null
            }
        }

    private fun observabilityImages(): List<String> {
        val templates: TemplateService = getKoin().get()
        val resources =
            OtelManifestBuilder(templates).buildAllResources() +
                JournaldOtelManifestBuilder(templates).buildAllResources() +
                EbpfExporterManifestBuilder().buildAllResources() +
                BeylaManifestBuilder(templates).buildAllResources() +
                MimirManifestBuilder(templates).buildAllResources() +
                LokiManifestBuilder(templates).buildAllResources() +
                TempoManifestBuilder(templates).buildAllResources() +
                PyroscopeManifestBuilder(templates).buildAllResources() +
                GrafanaManifestBuilder(templates).buildAllResources() +
                YaceManifestBuilder(templates).buildAllResources() +
                KubeStateMetricsManifestBuilder().buildAllResources()
        return podSpecs(resources).flatMap { pod ->
            (pod.containers.orEmpty() + pod.initContainers.orEmpty()).map { it.image }
        }
    }

    @Test
    fun `no observability image runs latest or an untagged reference`() {
        val images = observabilityImages()

        assertThat(images).isNotEmpty()
        assertThat(images).noneMatch { it.endsWith(":latest") }
        assertThat(images).allMatch { it.substringAfterLast("/").contains(":") || it.contains("@sha256:") }
    }

    @Test
    fun `the stack runs the versions the observability store requires`() {
        val images = observabilityImages().toSet()

        assertThat(images).contains(
            "grafana/mimir:3.2.1",
            "grafana/loki:3.7.8",
            "grafana/pyroscope:2.3.1",
            "grafana/tempo:3.0.3",
            "grafana/grafana:13.2.2",
            "grafana/grafana-image-renderer:v5.12.4",
            "grafana/alloy:v1.20.0",
            "grafana/beyla:3.36.0",
            "otel/opentelemetry-collector-contrib:0.161.0",
        )
        assertThat(images).noneMatch { it.startsWith("victoriametrics/") || it.startsWith("amazon/aws-cli") }
        assertThat(Constants.OtelCollector.VERSION).describedAs("EMR collector binary").isEqualTo("0.161.0")
    }
}
