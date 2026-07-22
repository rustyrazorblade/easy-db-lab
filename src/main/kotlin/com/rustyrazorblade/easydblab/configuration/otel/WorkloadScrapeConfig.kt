package com.rustyrazorblade.easydblab.configuration.otel

/**
 * Scrape target for a running K8s workload, read from the metrics registry ConfigMaps.
 *
 * The OTel scrape job name is `"$kitName-$jobName"` to ensure uniqueness in all cases:
 * kits with multiple scrape targets (e.g., kafka-exporter and kafka-jmx within the same kit) and
 * multiple kit instances sharing the same [jobName] (e.g., postgres-duckdb vs postgres-postgis).
 * The [jobName] is preserved as the `job` label in metrics via a relabel rule.
 *
 * Note: only username-based basic auth is supported. Password is intentionally omitted —
 * Trino's FIXED auth mode requires only a username with no real password. If a future kit
 * needs password-based scrape auth, extend this class, KitMetrics.Scrape, PrometheusBasicAuth,
 * and OtelManifestBuilder.buildConfigMap() together.
 *
 * When [podSelector] is set, the OTel job discovers and scrapes the workload's pods directly
 * (Prometheus `kubernetes_sd_configs`, role: pod) instead of a shared NodePort static target.
 * This gives every pod its own `instance` label — required for per-pod attribution (e.g. TiKV
 * per-store hotspots) that a NodePort target cannot provide, because the NodePort load-balances
 * each scrape across all backing pods. In this mode [port] is the pod's container metrics port,
 * not a NodePort. The selector is a comma-separated K8s label selector, e.g.
 * `"app.kubernetes.io/component=tikv,app.kubernetes.io/instance=tidb"`.
 */
data class WorkloadScrapeConfig(
    val kitName: String,
    val jobName: String,
    val port: Int,
    val path: String,
    val username: String = "",
    val podSelector: String = "",
)
