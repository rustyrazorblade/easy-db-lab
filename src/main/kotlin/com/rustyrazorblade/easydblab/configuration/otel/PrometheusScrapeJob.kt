package com.rustyrazorblade.easydblab.configuration.otel

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * HTTP basic auth for a scrape job whose target requires it. [password] defaults to empty for
 * targets that check only the user name.
 */
@Serializable
data class PrometheusBasicAuth(
    val username: String,
    val password: String = "",
)

/**
 * A Prometheus `scrape_config` entry as embedded in the OTel Collector's prometheus receiver.
 *
 * A job targets pods either statically ([staticConfigs], a fixed `localhost:port` NodePort) or via
 * pod service discovery ([kubernetesSdConfigs], role: pod). Exactly one of the two is populated;
 * the other stays null and is omitted from the rendered YAML.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class PrometheusScrapeJob(
    @SerialName("job_name") val jobName: String,
    @SerialName("scrape_interval") val scrapeInterval: String,
    @SerialName("metrics_path") val metricsPath: String,
    @SerialName("relabel_configs") val relabelConfigs: List<PrometheusRelabelConfig>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("static_configs") val staticConfigs: List<PrometheusStaticConfig>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("kubernetes_sd_configs") val kubernetesSdConfigs: List<PrometheusKubernetesSdConfig>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("basic_auth") val basicAuth: PrometheusBasicAuth? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("scrape_timeout") val scrapeTimeout: String? = null,
) {
    companion object {
        /**
         * The default indent for a scrape-job list embedded under
         * `receivers.prometheus.config.scrape_configs` in a collector config template: eight spaces,
         * the depth of the `- job_name:` entries. If that nesting changes, this must change with it.
         */
        const val SCRAPE_CONFIGS_INDENT = "        "

        /**
         * Renders [jobs] as the YAML body of a `scrape_configs:` list, each line prefixed by
         * [indent] so it drops into a collector config template at the right depth. Empty when there
         * are no jobs. Shared by the core and host-add-on collector builders so both emit identical
         * YAML for the same jobs.
         */
        fun render(
            jobs: List<PrometheusScrapeJob>,
            indent: String = SCRAPE_CONFIGS_INDENT,
        ): String {
            if (jobs.isEmpty()) return ""
            return Yaml()
                .encodeToString(ListSerializer(serializer()), jobs)
                .trimEnd()
                .lines()
                .joinToString("\n") { line -> if (line.isEmpty()) "" else "$indent$line" }
        }
    }
}

/**
 * A fixed target list for a scrape job, used when a workload is reached at a known address (a
 * NodePort on the node, or a localhost port) rather than found by pod discovery.
 */
@Serializable
data class PrometheusStaticConfig(
    val targets: List<String>,
)

/**
 * Prometheus Kubernetes service-discovery block. Only pod discovery scoped to a namespace is
 * used here, so [role] is always `"pod"` and [namespaces] restricts discovery to the workload's
 * namespace to avoid scanning the whole cluster.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class PrometheusKubernetesSdConfig(
    val role: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val namespaces: PrometheusSdNamespaces? = null,
)

/**
 * The namespaces a Kubernetes service-discovery block is limited to, so discovery lists only the
 * workload's own namespace instead of every pod in the cluster.
 */
@Serializable
data class PrometheusSdNamespaces(
    val names: List<String>,
)

/**
 * A single Prometheus relabel rule. Every field is optional so the same type expresses both the
 * simple static form (`target_label` + `replacement`) and the discovery form
 * (`source_labels` + `regex` + `action`, e.g. `keep`). Unset fields are omitted from the YAML,
 * letting Prometheus apply its own defaults.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class PrometheusRelabelConfig(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("source_labels") val sourceLabels: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("target_label") val targetLabel: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val regex: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val replacement: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val action: String? = null,
)
