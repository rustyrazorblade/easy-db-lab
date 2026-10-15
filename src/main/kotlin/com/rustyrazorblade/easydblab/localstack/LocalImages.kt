package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder

/**
 * Every image a local stack runs, from the same pins as the cluster.
 *
 * A component the cluster also runs takes the cluster's pin, so a local stack reads the store with
 * the exact versions that wrote it. A component only the local stacks run has its own released pin
 * in [Constants.LocalStack]. The drift test fails when a generated stack names an image that is not
 * here, and the export's README lists [all].
 */
object LocalImages {
    val MIMIR = MimirManifestBuilder.IMAGE
    val MEMCACHED = Constants.K8s.MIMIR_MEMCACHED_IMAGE
    val LOKI = LokiManifestBuilder.IMAGE
    val TEMPO = TempoManifestBuilder.IMAGE
    val GRAFANA = GrafanaManifestBuilder.GRAFANA_IMAGE
    val DOCUMENTS_WEB = Constants.Grafana.Documents.WEB_SERVER_IMAGE
    val SIGNER = Constants.Grafana.Documents.SIGV4_PROXY_IMAGE
    val GATE = Constants.LocalStack.GATE_IMAGE
    val HEALTHCHECK_TOOLS = Constants.LocalStack.HEALTHCHECK_TOOLS_IMAGE
    val THANOS = Constants.LocalStack.THANOS_IMAGE

    /** The images the cluster runs too, by component. */
    val CLUSTER_PINS: Map<String, String> =
        mapOf(
            "mimir" to MIMIR,
            "memcached" to MEMCACHED,
            "loki" to LOKI,
            "tempo" to TEMPO,
            "grafana" to GRAFANA,
            "documents web server" to DOCUMENTS_WEB,
            "aws-sigv4-proxy" to SIGNER,
        )

    /** The images only the local stacks run, by component. */
    val LOCAL_PINS: Map<String, String> =
        mapOf(
            "read gate and store check" to GATE,
            "signer healthcheck tools" to HEALTHCHECK_TOOLS,
            "thanos" to THANOS,
        )

    /** Every registered image. */
    val all: Set<String> = (CLUSTER_PINS.values + LOCAL_PINS.values).toSet()
}
