package com.rustyrazorblade.easydblab.configuration.stress

import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.TemplateService

/**
 * The OTel collector config that every stress job's sidecar container reads, and the ConfigMap that
 * carries it into the stress namespace.
 *
 * It exists apart from the Job so the Job's pod spec ([StressJobManifestBuilder]) and the job
 * lifecycle can change without touching the ConfigMap, and the reverse. The Job only needs the
 * ConfigMap's [CONFIG_MAP_NAME] and the config file's [FILE_NAME]; this class owns the content and
 * the creation.
 */
class StressSidecarConfig(
    private val templateService: TemplateService,
    private val k8sService: K8sService,
) {
    /** The sidecar config from its classpath resource, exporting to the node's core collector Service in [cluster]'s namespace. */
    fun render(cluster: StressCluster): String =
        templateService
            .fromResource(
                this::class.java,
                "/com/rustyrazorblade/easydblab/configuration/cassandra/$FILE_NAME",
            ).substitute(
                // The node's core collector, through its Service: no collector listens on the node
                // itself unless the host add-on runs there.
                mapOf("OTEL_CORE_GRPC_ENDPOINT" to cluster.serviceDns.collectorGrpcHostPort()),
            )

    /** Creates the sidecar ConfigMap in [cluster]'s namespace, or leaves it when it exists. */
    fun ensureConfigMap(cluster: StressCluster) {
        k8sService
            .createConfigMap(
                namespace = cluster.namespace.value,
                name = CONFIG_MAP_NAME,
                data = mapOf(FILE_NAME to render(cluster)),
                labels = mapOf("app.kubernetes.io/name" to "otel-stress-sidecar"),
            ).getOrThrow()
    }

    companion object {
        /** Name of the ConfigMap the Job mounts at `/etc/otel`. */
        const val CONFIG_MAP_NAME = "otel-stress-sidecar-config"

        /** Key of the collector config in the ConfigMap, and its resource file name. */
        const val FILE_NAME = "otel-stress-sidecar-config.yaml"
    }
}
