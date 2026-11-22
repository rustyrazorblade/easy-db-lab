package com.rustyrazorblade.easydblab.configuration

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
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.HasMetadata

/**
 * Every workload builder's resources at one [InstallScope], by workload name, with the real
 * [TemplateService]. The golden test pins them at the EC2 scope; the scope render test checks them
 * at another namespace. One list, so a new builder is covered by both.
 */
object AllBuilderManifests {
    private const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    /** Each workload's resources at [scope]. */
    fun render(
        templates: TemplateService,
        scope: InstallScope,
    ): Map<String, List<HasMetadata>> =
        mapOf(
            "loki" to LokiManifestBuilder(templates).buildAllResources(scope),
            "mimir" to MimirManifestBuilder(templates).buildAllResources(scope),
            "tempo" to TempoManifestBuilder(templates).buildAllResources(scope),
            "grafana" to
                GrafanaManifestBuilder(templates).buildAllResources(scope, DocumentsBucket("acct", "us-west-2"), ObservabilityHosting.AWS),
            "pyroscope" to PyroscopeManifestBuilder(templates).buildAllResources(scope),
            "registry" to RegistryManifestBuilder().buildAllResources(scope),
            "s3manager" to S3ManagerManifestBuilder(templates).buildAllResources(scope),
            "yace" to YaceManifestBuilder(templates).buildAllResources(scope),
            "kube-state-metrics" to KubeStateMetricsManifestBuilder().buildAllResources(scope),
            "compactor" to
                CompactorManifestBuilder(templates)
                    .buildAllResources(scope, CompactorAgentJar(SHA), S3Backend(bucket = "acct", region = "eu-west-1")),
            "otel" to OtelManifestBuilder(templates).buildAllResources(scope, NodeCapabilities.AWS),
            "otel-host" to OtelHostCollectorManifestBuilder(templates).buildAllResources(scope, CniMode.Cilium),
            "journald-otel" to JournaldOtelManifestBuilder(templates).buildAllResources(scope),
            "ebpf-exporter" to EbpfExporterManifestBuilder().buildAllResources(scope),
            "beyla" to BeylaManifestBuilder(templates).buildAllResources(scope),
            "sidecar" to SidecarManifestBuilder(templates).buildAllResources(scope, "sidecar:test", "test-1", "tenant"),
        )
}
