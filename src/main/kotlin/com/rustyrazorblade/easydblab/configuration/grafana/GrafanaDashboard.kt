package com.rustyrazorblade.easydblab.configuration.grafana

/**
 * Registry of all Grafana dashboards.
 *
 * Each entry defines the metadata needed to build a K8s ConfigMap and wire it
 * into the Grafana deployment as a volume mount. Adding a new dashboard requires
 * only a new enum entry and a JSON resource file.
 *
 * @property configMapName K8s ConfigMap name
 * @property volumeName Volume name in the Grafana Deployment spec
 * @property mountPath Where Grafana reads the dashboard JSON inside the container
 * @property jsonFileName File name inside the ConfigMap data key
 * @property resourcePath Classpath resource path (relative to this class) for the dashboard JSON
 * @property optional Whether the volume mount uses optional: true (for dashboards that may not exist)
 */
enum class GrafanaDashboard(
    val configMapName: String,
    val volumeName: String,
    val mountPath: String,
    val jsonFileName: String,
    val resourcePath: String,
    val optional: Boolean = false,
) {
    SYSTEM(
        configMapName = "grafana-dashboard-system",
        volumeName = "dashboard-system",
        mountPath = "/var/lib/grafana/dashboards/system",
        jsonFileName = "system-overview.json",
        resourcePath = "dashboards/system-overview.json",
    ),
    S3(
        configMapName = "grafana-dashboard-s3",
        volumeName = "dashboard-s3",
        mountPath = "/var/lib/grafana/dashboards/s3",
        jsonFileName = "s3-cloudwatch.json",
        resourcePath = "dashboards/s3-cloudwatch.json",
        optional = true,
    ),
    EMR(
        configMapName = "grafana-dashboard-emr",
        volumeName = "dashboard-emr",
        mountPath = "/var/lib/grafana/dashboards/emr",
        jsonFileName = "emr.json",
        resourcePath = "dashboards/emr.json",
        optional = true,
    ),
    OPENSEARCH(
        configMapName = "grafana-dashboard-opensearch",
        volumeName = "dashboard-opensearch",
        mountPath = "/var/lib/grafana/dashboards/opensearch",
        jsonFileName = "opensearch.json",
        resourcePath = "dashboards/opensearch.json",
        optional = true,
    ),
    STRESS(
        configMapName = "grafana-dashboard-stress",
        volumeName = "dashboard-stress",
        mountPath = "/var/lib/grafana/dashboards/stress",
        jsonFileName = "stress.json",
        resourcePath = "dashboards/stress.json",
        optional = true,
    ),
    CLICKHOUSE(
        configMapName = "grafana-dashboard-clickhouse",
        volumeName = "dashboard-clickhouse",
        mountPath = "/var/lib/grafana/dashboards/clickhouse",
        jsonFileName = "clickhouse.json",
        resourcePath = "dashboards/clickhouse.json",
        optional = true,
    ),
    CLICKHOUSE_LOGS(
        configMapName = "grafana-dashboard-clickhouse-logs",
        volumeName = "dashboard-clickhouse-logs",
        mountPath = "/var/lib/grafana/dashboards/clickhouse-logs",
        jsonFileName = "clickhouse-logs.json",
        resourcePath = "dashboards/clickhouse-logs.json",
        optional = true,
    ),
    PROFILING(
        configMapName = "grafana-dashboard-profiling",
        volumeName = "dashboard-profiling",
        mountPath = "/var/lib/grafana/dashboards/profiling",
        jsonFileName = "profiling.json",
        resourcePath = "dashboards/profiling.json",
        optional = true,
    ),
    CASSANDRA_CONDENSED(
        configMapName = "grafana-dashboard-cassandra-condensed",
        volumeName = "dashboard-cassandra-condensed",
        mountPath = "/var/lib/grafana/dashboards/cassandra-condensed",
        jsonFileName = "cassandra-condensed.json",
        resourcePath = "dashboards/cassandra-condensed.json",
        optional = true,
    ),
    CASSANDRA_OVERVIEW(
        configMapName = "grafana-dashboard-cassandra-overview",
        volumeName = "dashboard-cassandra-overview",
        mountPath = "/var/lib/grafana/dashboards/cassandra-overview",
        jsonFileName = "cassandra-overview.json",
        resourcePath = "dashboards/cassandra-overview.json",
        optional = true,
    ),
}
