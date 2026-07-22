package com.rustyrazorblade.easydblab.configuration.otel

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
)

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
