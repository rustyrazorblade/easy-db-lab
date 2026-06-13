package com.rustyrazorblade.easydblab.configuration.otel

/**
 * Scrape target for a running K8s workload, read from the metrics registry ConfigMaps.
 *
 * [kitName] becomes the OTel scrape job name to ensure uniqueness across multiple kit instances
 * that share the same [jobName] (e.g., postgres-duckdb vs postgres-postgis both declare job "postgres").
 * The [jobName] is preserved as the `job` label in metrics via a relabel rule.
 *
 * Note: only username-based basic auth is supported. Password is intentionally omitted —
 * Trino's FIXED auth mode requires only a username with no real password. If a future kit
 * needs password-based scrape auth, extend this class, KitMetrics.Scrape, PrometheusBasicAuth,
 * and OtelManifestBuilder.buildConfigMap() together.
 */
data class WorkloadScrapeConfig(
    val kitName: String,
    val jobName: String,
    val port: Int,
    val path: String,
    val username: String = "",
)
