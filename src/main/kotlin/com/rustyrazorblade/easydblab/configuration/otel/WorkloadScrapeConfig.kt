package com.rustyrazorblade.easydblab.configuration.otel

/**
 * Scrape target for a running K8s workload, read from the metrics registry ConfigMaps.
 */
data class WorkloadScrapeConfig(
    val jobName: String,
    val port: Int,
    val path: String,
)
