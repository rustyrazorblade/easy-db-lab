package com.rustyrazorblade.easydblab.configuration.otel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PrometheusScrapeJob(
    @SerialName("job_name") val jobName: String,
    @SerialName("scrape_interval") val scrapeInterval: String,
    @SerialName("static_configs") val staticConfigs: List<PrometheusStaticConfig>,
    @SerialName("metrics_path") val metricsPath: String,
    @SerialName("relabel_configs") val relabelConfigs: List<PrometheusRelabelConfig>,
)

@Serializable
data class PrometheusStaticConfig(
    val targets: List<String>,
)

@Serializable
data class PrometheusRelabelConfig(
    @SerialName("target_label") val targetLabel: String,
    val replacement: String,
)
