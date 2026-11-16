package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.YamlTestSupport.keysAt

/**
 * The component IDs the OpenTelemetry Collector 0.161.0 reports as deprecated aliases at startup
 * (`"<old>" alias is deprecated; use "<new>" instead`), by component kind.
 *
 * Collected by running the pinned collector against the previous configuration and reading its
 * warnings, so this list is what the binary says rather than what a changelog implied.
 */
object DeprecatedCollectorComponents {
    private val deprecated =
        mapOf(
            "receivers" to setOf("filelog", "hostmetrics"),
            "processors" to setOf("k8sattributes", "resourcedetection", "deltatocumulative"),
            "exporters" to setOf("otlp", "otlphttp", "prometheusremotewrite"),
            "connectors" to setOf("spanmetrics", "servicegraph", "signaltometrics"),
        )

    /** Every `kind/id` in [yaml] whose type is a deprecated alias. */
    fun findIn(yaml: String): List<String> =
        deprecated.flatMap { (kind, types) ->
            keysAt(yaml, kind)
                .filter { it.substringBefore("/") in types }
                .map { "$kind/$it" }
        }
}
