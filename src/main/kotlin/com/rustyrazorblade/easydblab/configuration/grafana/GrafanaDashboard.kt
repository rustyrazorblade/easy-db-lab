package com.rustyrazorblade.easydblab.configuration.grafana

/** Provider path for dashboards that appear at the root of Grafana's dashboard list. */
const val GRAFANA_DASHBOARD_ROOT = "/var/lib/grafana/dashboards"

/** Name of the Grafana folder holding the Cassandra dashboards. */
const val GRAFANA_CASSANDRA_FOLDER = "Cassandra"

/**
 * Provider path backing [GRAFANA_CASSANDRA_FOLDER]. Must match `options.path` of the `cassandra`
 * provider in `dashboards.yaml`.
 */
const val GRAFANA_CASSANDRA_PATH = "$GRAFANA_DASHBOARD_ROOT-cassandra"

/** Name of the Grafana folder holding the engine-agnostic host and system dashboards. */
const val GRAFANA_INFRASTRUCTURE_FOLDER = "Infrastructure"

/**
 * Provider path backing [GRAFANA_INFRASTRUCTURE_FOLDER]. Must match `options.path` of the
 * `infrastructure` provider in `dashboards.yaml`.
 */
const val GRAFANA_INFRASTRUCTURE_PATH = "$GRAFANA_DASHBOARD_ROOT-infrastructure"

/**
 * Registry of all Grafana dashboards.
 *
 * Each entry defines the metadata needed to build a K8s ConfigMap and wire it
 * into the Grafana deployment as a volume mount. Adding a new dashboard requires
 * only a new enum entry and a JSON file in the top-level `dashboards/` directory.
 *
 * Folders come from the provisioning provider a dashboard's [mountPath] falls under, not from the
 * directory name: `dashboards.yaml` declares one provider per folder, each naming its folder
 * outright. [folder] records which one an entry belongs to, so [folderPath] and the mount path can
 * be checked against each other.
 *
 * @property configMapName K8s ConfigMap name
 * @property volumeName Volume name in the Grafana Deployment spec
 * @property mountPath Where Grafana reads the dashboard JSON inside the container
 * @property jsonFileName File name used as the ConfigMap data key and classpath resource path
 * @property optional Whether the dashboard may be absent. An optional dashboard with no JSON on the
 *   classpath is skipped when building ConfigMaps, and its volume mount uses `optional: true`, so a
 *   missing file can never stop Grafana from starting.
 * @property folder Grafana folder this dashboard belongs to; empty means the root of the list
 */
enum class GrafanaDashboard(
    val configMapName: String,
    val volumeName: String,
    val mountPath: String,
    val jsonFileName: String,
    val optional: Boolean = false,
    val folder: String = "",
) {
    // Not optional, unlike every other entry: this is the home dashboard, named by
    // GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH, so a missing JSON should fail the build loudly
    // rather than be skipped into a Grafana that opens on nothing.
    SYSTEM(
        configMapName = "grafana-dashboard-system",
        volumeName = "dashboard-system",
        mountPath = "$GRAFANA_INFRASTRUCTURE_PATH/system",
        jsonFileName = "system-overview.json",
        folder = GRAFANA_INFRASTRUCTURE_FOLDER,
    ),
    SYSTEM_AB_COMPARISON(
        configMapName = "grafana-dashboard-system-ab-comparison",
        volumeName = "dashboard-system-ab-comparison",
        mountPath = "$GRAFANA_INFRASTRUCTURE_PATH/system-ab-comparison",
        jsonFileName = "system-ab-comparison.json",
        optional = true,
        folder = GRAFANA_INFRASTRUCTURE_FOLDER,
    ),
    INSTANCE_CLOUD(
        configMapName = "grafana-dashboard-instance-cloud",
        volumeName = "dashboard-instance-cloud",
        mountPath = "$GRAFANA_INFRASTRUCTURE_PATH/instance-cloud",
        jsonFileName = "instance-cloud.json",
        optional = true,
        folder = GRAFANA_INFRASTRUCTURE_FOLDER,
    ),
    PROFILER_HEALTH(
        configMapName = "grafana-dashboard-profiler-health",
        volumeName = "dashboard-profiler-health",
        mountPath = "$GRAFANA_INFRASTRUCTURE_PATH/profiler-health",
        jsonFileName = "profiler-health.json",
        optional = true,
        folder = GRAFANA_INFRASTRUCTURE_FOLDER,
    ),
    S3(
        configMapName = "grafana-dashboard-s3",
        volumeName = "dashboard-s3",
        mountPath = "/var/lib/grafana/dashboards/s3",
        jsonFileName = "s3-cloudwatch.json",
        optional = true,
    ),
    EMR(
        configMapName = "grafana-dashboard-emr",
        volumeName = "dashboard-emr",
        mountPath = "/var/lib/grafana/dashboards/emr",
        jsonFileName = "emr.json",
        optional = true,
    ),
    OPENSEARCH(
        configMapName = "grafana-dashboard-opensearch",
        volumeName = "dashboard-opensearch",
        mountPath = "/var/lib/grafana/dashboards/opensearch",
        jsonFileName = "opensearch.json",
        optional = true,
    ),
    STRESS(
        configMapName = "grafana-dashboard-stress",
        volumeName = "dashboard-stress",
        mountPath = "/var/lib/grafana/dashboards/stress",
        jsonFileName = "stress.json",
        optional = true,
    ),
    CLICKHOUSE(
        configMapName = "grafana-dashboard-clickhouse",
        volumeName = "dashboard-clickhouse",
        mountPath = "/var/lib/grafana/dashboards/clickhouse",
        jsonFileName = "clickhouse.json",
        optional = true,
    ),
    CLICKHOUSE_LOGS(
        configMapName = "grafana-dashboard-clickhouse-logs",
        volumeName = "dashboard-clickhouse-logs",
        mountPath = "/var/lib/grafana/dashboards/clickhouse-logs",
        jsonFileName = "clickhouse-logs.json",
        optional = true,
    ),
    PROFILING(
        configMapName = "grafana-dashboard-profiling",
        volumeName = "dashboard-profiling",
        mountPath = "/var/lib/grafana/dashboards/profiling",
        jsonFileName = "profiling.json",
        optional = true,
    ),
    CASSANDRA_OVERVIEW(
        configMapName = "grafana-dashboard-cassandra-overview",
        volumeName = "dashboard-cassandra-overview",
        mountPath = "$GRAFANA_CASSANDRA_PATH/cassandra-overview",
        jsonFileName = "cassandra-overview.json",
        optional = true,
        folder = GRAFANA_CASSANDRA_FOLDER,
    ),
    LOG_INVESTIGATION(
        configMapName = "grafana-dashboard-log-investigation",
        volumeName = "dashboard-log-investigation",
        mountPath = "/var/lib/grafana/dashboards/log-investigation",
        jsonFileName = "log-investigation.json",
        optional = true,
    ),
    CLUSTER_COMPARISON(
        configMapName = "grafana-dashboard-cluster-comparison",
        volumeName = "dashboard-cluster-comparison",
        mountPath = "$GRAFANA_CASSANDRA_PATH/cluster-comparison",
        jsonFileName = "cluster-comparison.json",
        optional = true,
        folder = GRAFANA_CASSANDRA_FOLDER,
    ),
    CASSANDRA_JVM(
        configMapName = "grafana-dashboard-cassandra-jvm",
        volumeName = "dashboard-cassandra-jvm",
        mountPath = "$GRAFANA_CASSANDRA_PATH/cassandra-jvm",
        jsonFileName = "cassandra-jvm.json",
        optional = true,
        folder = GRAFANA_CASSANDRA_FOLDER,
    ),
    READ_PATH_ANATOMY(
        configMapName = "grafana-dashboard-read-path-anatomy",
        volumeName = "dashboard-read-path-anatomy",
        mountPath = "$GRAFANA_CASSANDRA_PATH/read-path-anatomy",
        jsonFileName = "read-path-anatomy.json",
        optional = true,
        folder = GRAFANA_CASSANDRA_FOLDER,
    ),
    TABLE_DEEP_DIVE(
        configMapName = "grafana-dashboard-table-deep-dive",
        volumeName = "dashboard-table-deep-dive",
        mountPath = "$GRAFANA_CASSANDRA_PATH/table-deep-dive",
        jsonFileName = "table-deep-dive.json",
        optional = true,
        folder = GRAFANA_CASSANDRA_FOLDER,
    ),
    WRITE_PATH_BACKPRESSURE(
        configMapName = "grafana-dashboard-write-path-backpressure",
        volumeName = "dashboard-write-path-backpressure",
        mountPath = "$GRAFANA_CASSANDRA_PATH/write-path-backpressure",
        jsonFileName = "write-path-backpressure.json",
        optional = true,
        folder = GRAFANA_CASSANDRA_FOLDER,
    ),
    NODE_DIVERGENCE(
        configMapName = "grafana-dashboard-node-divergence",
        volumeName = "dashboard-node-divergence",
        mountPath = "$GRAFANA_CASSANDRA_PATH/node-divergence",
        jsonFileName = "node-divergence.json",
        optional = true,
        folder = GRAFANA_CASSANDRA_FOLDER,
    ),
    AB_COMPARISON(
        configMapName = "grafana-dashboard-ab-comparison",
        volumeName = "dashboard-ab-comparison",
        mountPath = "$GRAFANA_CASSANDRA_PATH/ab-comparison",
        jsonFileName = "ab-comparison.json",
        optional = true,
        folder = GRAFANA_CASSANDRA_FOLDER,
    ),
    PROFILE_COMPARISON(
        configMapName = "grafana-dashboard-profile-comparison",
        volumeName = "dashboard-profile-comparison",
        mountPath = "$GRAFANA_CASSANDRA_PATH/profile-comparison",
        jsonFileName = "profile-comparison.json",
        optional = true,
        folder = GRAFANA_CASSANDRA_FOLDER,
    ),
    CLIENT_VS_SERVER_LATENCY(
        configMapName = "grafana-dashboard-client-vs-server-latency",
        volumeName = "dashboard-client-vs-server-latency",
        mountPath = "$GRAFANA_CASSANDRA_PATH/client-vs-server-latency",
        jsonFileName = "client-vs-server-latency.json",
        optional = true,
        folder = GRAFANA_CASSANDRA_FOLDER,
    ),
    COMPACTION_STORAGE(
        configMapName = "grafana-dashboard-compaction-storage",
        volumeName = "dashboard-compaction-storage",
        mountPath = "$GRAFANA_CASSANDRA_PATH/compaction-storage",
        jsonFileName = "compaction-storage.json",
        optional = true,
        folder = GRAFANA_CASSANDRA_FOLDER,
    ),
    TRACE_RED(
        configMapName = "grafana-dashboard-trace-red",
        volumeName = "dashboard-trace-red",
        mountPath = "$GRAFANA_CASSANDRA_PATH/trace-red",
        jsonFileName = "trace-red.json",
        optional = true,
        folder = GRAFANA_CASSANDRA_FOLDER,
    ),
    CASSANDRA_LOGS_ANALYSIS(
        configMapName = "grafana-dashboard-cassandra-logs-analysis",
        volumeName = "dashboard-cassandra-logs-analysis",
        mountPath = "$GRAFANA_CASSANDRA_PATH/cassandra-logs-analysis",
        jsonFileName = "cassandra-logs-analysis.json",
        optional = true,
        folder = GRAFANA_CASSANDRA_FOLDER,
    ),
    COMMITLOG_MEMTABLE(
        configMapName = "grafana-dashboard-commitlog-memtable",
        volumeName = "dashboard-commitlog-memtable",
        mountPath = "$GRAFANA_CASSANDRA_PATH/commitlog-memtable",
        jsonFileName = "commitlog-memtable.json",
        optional = true,
        folder = GRAFANA_CASSANDRA_FOLDER,
    ),
    TEMPO(
        configMapName = "grafana-dashboard-tempo",
        volumeName = "dashboard-tempo",
        mountPath = "/var/lib/grafana/dashboards/tempo",
        jsonFileName = "tempo.json",
        optional = true,
    ),
    ;

    /**
     * Provisioning path whose provider owns this dashboard's folder.
     *
     * [mountPath] must sit under it, or the dashboard lands in the wrong folder - or in none,
     * if no provider sweeps the path it was mounted at.
     */
    val folderPath: String
        get() = if (folder.isEmpty()) GRAFANA_DASHBOARD_ROOT else "$GRAFANA_DASHBOARD_ROOT-${folder.lowercase()}"
}
