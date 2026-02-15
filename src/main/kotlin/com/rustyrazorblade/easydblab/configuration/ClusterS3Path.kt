package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants

/**
 * Immutable S3 path abstraction following java.nio.file.Path patterns.
 * Provides type-safe S3 path construction for per-environment buckets.
 *
 * Each environment has its own dedicated S3 bucket, so paths are organized
 * by technology subdirectories (cassandra/, clickhouse/, spark/) rather than
 * cluster IDs.
 *
 * Example usage:
 * ```
 * val s3Path = ClusterS3Path.from(clusterState)
 * val jarPath = s3Path.spark().resolve("myapp.jar")
 * println(jarPath) // s3://easy-db-lab-mycluster-abc123/spark/myapp.jar
 *
 * // For S3 SDK calls:
 * val putRequest = PutObjectRequest.builder()
 *     .bucket(jarPath.bucket)
 *     .key(jarPath.getKey())
 *     .build()
 * ```
 *
 * @property bucket The S3 bucket name (without s3:// prefix)
 * @property segments The path segments after the bucket (immutable list)
 */
data class ClusterS3Path(
    val bucket: String,
    private val segments: List<String> = emptyList(),
) {
    companion object {
        private const val CASSANDRA_DIR = "cassandra"
        private const val CLICKHOUSE_DIR = "clickhouse"
        private const val SPARK_DIR = "spark"
        private const val EMR_LOGS_DIR = "emr-logs"
        private const val BACKUPS_DIR = "backups"
        private const val LOGS_DIR = "logs"
        private const val DATA_DIR = "data"
        private const val KUBECONFIG_FILE = "kubeconfig"
        private const val K8S_DIR = "k8s"
        private const val CONFIG_DIR = "config"
        private const val CASSANDRA_PATCH_FILE = "cassandra.patch.yaml"
        private const val CASSANDRA_CONFIG_DIR = "cassandra-config"
        private const val CASSANDRA_VERSIONS_FILE = "cassandra_versions.yaml"
        private const val ENVIRONMENT_FILE = "environment.sh"
        private const val SETUP_INSTANCE_FILE = "setup_instance.sh"
        private const val TEMPO_DIR = "tempo"
        private const val VICTORIA_METRICS_DIR = "victoriametrics"
        private const val VICTORIA_LOGS_DIR = "victorialogs"

        /**
         * Create a ClusterS3Path from ClusterState.
         * Uses the per-environment bucket stored in ClusterState.
         *
         * @param clusterState The cluster state containing s3Bucket
         * @return A new ClusterS3Path for this cluster's bucket
         * @throws IllegalStateException if s3Bucket is not configured
         */
        fun from(clusterState: ClusterState): ClusterS3Path {
            val bucket =
                clusterState.s3Bucket
                    ?: error("S3 bucket not configured for cluster '${clusterState.name}'. Run 'easy-db-lab up' first.")
            val clusterPrefix = "${Constants.S3.CLUSTERS_PREFIX}/${clusterState.name}-${clusterState.clusterId}"
            return ClusterS3Path(bucket, clusterPrefix.split("/"))
        }

        /**
         * Create a root S3 path for a specific bucket.
         *
         * @param bucket The S3 bucket name
         * @return A new ClusterS3Path at bucket root
         */
        fun root(bucket: String): ClusterS3Path = ClusterS3Path(bucket)

        /**
         * Create a ClusterS3Path from an S3 object key.
         * Properly handles key strings by filtering empty segments.
         *
         * This should be used when reconstructing paths from S3 list operations
         * where the full key is returned.
         *
         * @param bucket The S3 bucket name
         * @param key The S3 object key (e.g., "spark/myapp.jar")
         * @return A new ClusterS3Path representing the key
         */
        fun fromKey(
            bucket: String,
            key: String,
        ): ClusterS3Path = ClusterS3Path(bucket, key.split("/").filter { it.isNotBlank() })
    }

    // Core Path-like methods

    /**
     * Resolve a path segment, returning a new ClusterS3Path.
     * Follows java.nio.file.Path.resolve() semantics.
     *
     * Path segments containing slashes are split into multiple segments.
     * Empty segments are filtered out.
     *
     * Example:
     * ```
     * path.resolve("subdir").resolve("file.jar")
     * path.resolve("subdir/file.jar")  // Equivalent
     * ```
     *
     * @param path The path segment(s) to append
     * @return A new ClusterS3Path with the path appended
     */
    fun resolve(path: String): ClusterS3Path {
        val newSegments = path.split("/").filter { it.isNotBlank() }
        return copy(segments = segments + newSegments)
    }

    /**
     * Get parent path, or null if this is the root.
     * Follows java.nio.file.Path.getParent() semantics.
     *
     * @return The parent path, or null if this path has no parent
     */
    fun getParent(): ClusterS3Path? =
        if (segments.isEmpty()) {
            null
        } else {
            copy(segments = segments.dropLast(1))
        }

    /**
     * Get the last segment (filename), or null if this is the root.
     * Follows java.nio.file.Path.getFileName() semantics.
     *
     * @return The filename, or null if this is the root path
     */
    fun getFileName(): String? = segments.lastOrNull()

    /**
     * Returns the S3 URI as a string: s3://bucket/path/to/file
     *
     * @return The full S3 URI
     */
    override fun toString(): String {
        val pathPart = if (segments.isEmpty()) "" else segments.joinToString("/")
        return if (pathPart.isEmpty()) {
            "s3://$bucket"
        } else {
            "s3://$bucket/$pathPart"
        }
    }

    /**
     * Alias for toString() to match URI patterns.
     *
     * @return The full S3 URI
     */
    fun toUri(): String = toString()

    /**
     * Returns just the path portion without the s3://bucket prefix.
     * Useful for S3 SDK calls that require bucket and key separately.
     *
     * Example:
     * ```
     * val path = ClusterS3Path.root("bucket").resolve("file.txt")
     * putRequest.bucket(path.bucket).key(path.getKey())
     * ```
     *
     * @return The S3 key (path after bucket name)
     */
    fun getKey(): String = segments.joinToString("/")

    // Convenience methods for technology-specific directories

    /**
     * Path for Cassandra data and backups.
     *
     * @return Path: s3://bucket/cassandra
     */
    fun cassandra(): ClusterS3Path = resolve(CASSANDRA_DIR)

    /**
     * Path for ClickHouse data.
     *
     * @return Path: s3://bucket/clickhouse
     */
    fun clickhouse(): ClusterS3Path = resolve(CLICKHOUSE_DIR)

    /**
     * Path for Spark JARs and data.
     *
     * @return Path: s3://bucket/spark
     */
    fun spark(): ClusterS3Path = resolve(SPARK_DIR)

    /**
     * Path for EMR logs.
     *
     * @return Path: s3://bucket/spark/emr-logs
     */
    fun emrLogs(): ClusterS3Path = resolve(SPARK_DIR).resolve(EMR_LOGS_DIR)

    /**
     * Path for backups.
     *
     * @return Path: s3://bucket/backups
     */
    fun backups(): ClusterS3Path = resolve(BACKUPS_DIR)

    /**
     * Path for log aggregation.
     *
     * @return Path: s3://bucket/logs
     */
    fun logs(): ClusterS3Path = resolve(LOGS_DIR)

    /**
     * Path for general data storage.
     *
     * @return Path: s3://bucket/data
     */
    fun data(): ClusterS3Path = resolve(DATA_DIR)

    /**
     * Path for Tempo trace storage.
     *
     * @return Path: s3://bucket/tempo
     */
    fun tempo(): ClusterS3Path = resolve(TEMPO_DIR)

    /**
     * Path for K3s kubeconfig file.
     *
     * @return Path: s3://bucket/config/kubeconfig
     */
    fun kubeconfig(): ClusterS3Path = resolve(CONFIG_DIR).resolve(KUBECONFIG_FILE)

    /**
     * Path for Kubernetes manifests directory.
     *
     * @return Path: s3://bucket/config/k8s
     */
    fun k8s(): ClusterS3Path = resolve(CONFIG_DIR).resolve(K8S_DIR)

    /**
     * Path for cluster configuration files directory.
     *
     * @return Path: s3://bucket/config
     */
    fun config(): ClusterS3Path = resolve(CONFIG_DIR)

    /**
     * Path for Cassandra patch configuration file.
     *
     * @return Path: s3://bucket/config/cassandra.patch.yaml
     */
    fun cassandraPatch(): ClusterS3Path = resolve(CONFIG_DIR).resolve(CASSANDRA_PATCH_FILE)

    /**
     * Path for Cassandra configuration directory (local cassandra/ dir).
     *
     * @return Path: s3://bucket/config/cassandra-config
     */
    fun cassandraConfig(): ClusterS3Path = resolve(CONFIG_DIR).resolve(CASSANDRA_CONFIG_DIR)

    /**
     * Path for cassandra_versions.yaml file.
     *
     * @return Path: s3://bucket/config/cassandra_versions.yaml
     */
    fun cassandraVersions(): ClusterS3Path = resolve(CONFIG_DIR).resolve(CASSANDRA_VERSIONS_FILE)

    /**
     * Path for environment.sh file.
     *
     * @return Path: s3://bucket/config/environment.sh
     */
    fun environmentScript(): ClusterS3Path = resolve(CONFIG_DIR).resolve(ENVIRONMENT_FILE)

    /**
     * Path for setup_instance.sh file.
     *
     * @return Path: s3://bucket/config/setup_instance.sh
     */
    fun setupInstanceScript(): ClusterS3Path = resolve(CONFIG_DIR).resolve(SETUP_INSTANCE_FILE)

    /**
     * Path for VictoriaMetrics backups.
     *
     * @return Path: s3://bucket/victoriametrics
     */
    fun victoriaMetrics(): ClusterS3Path = resolve(VICTORIA_METRICS_DIR)

    /**
     * Path for VictoriaLogs backups.
     *
     * @return Path: s3://bucket/victorialogs
     */
    fun victoriaLogs(): ClusterS3Path = resolve(VICTORIA_LOGS_DIR)
}
