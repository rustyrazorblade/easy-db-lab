package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.configuration.store.StoreBackend

/**
 * Immutable, storage-neutral object location following java.nio.file.Path patterns: a bucket, a
 * store prefix and key segments, with no URI scheme.
 *
 * All clusters within an account share a single bucket, with each cluster's data isolated under a
 * cluster-specific prefix (clusters/<name>-<id>/). Within each cluster prefix, paths are organized
 * by technology subdirectories (cassandra/, clickhouse/, spark/). Observability data (traces,
 * profiles, metrics, logs, annotations) does not live here; it lives under the tool-named roots
 * (`mimir/`, `loki/`, `tempo/`, `pyroscope/`, `grafana/`) in the same bucket, laid out by
 * [ObservabilityStore].
 *
 * The store prefix leads every key, so one bucket can hold several stores side by side. It is
 * empty by default, which gives exactly the keys easy-db-lab has always used.
 *
 * This type knows nothing about any one storage technology. [toString] is `bucket/key` and is for
 * logs only; a location shown to the user is rendered by the adapter that stores it
 * (`ObjectStore.uriOf`), and an `s3://` URI is parsed by `services/aws/S3Uri`.
 *
 * Example usage:
 * ```
 * val clusterPath = ClusterStoragePath.from(clusterState)
 * val jarPath = clusterPath.spark().resolve("myapp.jar")
 * objectStore.uriOf(jarPath) // s3://my-account-bucket/clusters/mycluster-abc123/spark/myapp.jar on AWS
 *
 * // For SDK calls:
 * val putRequest = PutObjectRequest.builder()
 *     .bucket(jarPath.bucket)
 *     .key(jarPath.getKey())
 *     .build()
 * ```
 *
 * @property bucket The bucket name, with no scheme.
 * @property prefix The store prefix in front of every key; empty for none.
 * @property segments The path segments after the store prefix (immutable list).
 */
data class ClusterStoragePath(
    val bucket: String,
    val prefix: String = "",
    private val segments: List<String> = emptyList(),
) {
    companion object {
        internal const val CASSANDRA_DIR = "cassandra"
        internal const val CLICKHOUSE_DIR = "clickhouse"
        internal const val SPARK_DIR = "spark"
        internal const val EMR_LOGS_DIR = "emr-logs"
        internal const val BACKUPS_DIR = "backups"
        internal const val LOGS_DIR = "logs"
        internal const val DATA_DIR = "data"
        internal const val STATE_JSON_FILE = "state.json"
        internal const val KUBECONFIG_FILE = "kubeconfig"
        internal const val K8S_DIR = "k8s"
        internal const val CONFIG_DIR = "config"
        internal const val CASSANDRA_PATCH_FILE = "cassandra.patch.yaml"
        internal const val CASSANDRA_VERSIONS_FILE = "cassandra_versions.yaml"
        internal const val ENV_SCRIPT_FILE = "env.sh"
        internal const val ENVIRONMENT_FILE = "environment.sh"
        internal const val SETUP_INSTANCE_FILE = "setup_instance.sh"
        internal const val CLICKHOUSE_BACKUPS_DIR = "clickhouse-backups"
        internal const val CASSANDRA_BUILDS_DIR = "cassandra-builds"

        /**
         * Create a ClusterStoragePath from ClusterState.
         * Uses the account-level bucket, the cluster's store prefix and its cluster prefix.
         *
         * @param clusterState The cluster state containing s3Bucket
         * @param storePrefix The store prefix in front of the key; the cluster's [StoreBackend] prefix by default
         * @return A new ClusterStoragePath for this cluster's prefix within the account bucket
         * @throws IllegalStateException if s3Bucket is not configured
         */
        fun from(
            clusterState: ClusterState,
            storePrefix: String = StoreBackend.prefixOf(clusterState),
        ): ClusterStoragePath {
            val bucket =
                clusterState.s3Bucket
                    ?: error("S3 bucket not configured for cluster '${clusterState.name}'. Run 'easy-db-lab up' first.")
            return ClusterStoragePath(bucket, storePrefix, clusterState.clusterPrefix().split("/"))
        }

        /**
         * Create the root path of a bucket under a store prefix.
         *
         * @param bucket The bucket name
         * @param prefix The store prefix every key starts with; empty for none
         * @return A new ClusterStoragePath at the root of the store
         */
        fun root(
            bucket: String,
            prefix: String = "",
        ): ClusterStoragePath = ClusterStoragePath(bucket, prefix)

        /**
         * Create a ClusterStoragePath from an S3 object key.
         * Properly handles key strings by filtering empty segments.
         *
         * This should be used when reconstructing paths from S3 list operations
         * where the full key is returned.
         *
         * The path it returns has no store prefix: every segment of [key], a store prefix included,
         * becomes a key segment, so [getKey] gives back [key] unchanged. Never resolve a listed key
         * onto a path built with a store prefix ([root] or [from]); the prefix would appear twice.
         *
         * @param bucket The S3 bucket name
         * @param key The S3 object key (e.g., "spark/myapp.jar")
         * @return A new ClusterStoragePath representing the key
         */
        fun fromKey(
            bucket: String,
            key: String,
        ): ClusterStoragePath = ClusterStoragePath(bucket, segments = key.split("/").filter { it.isNotBlank() })

        /** Root path for ClickHouse backups in the account bucket, decoupled from any cluster lifecycle. */
        fun clickhouseBackupsRoot(accountBucket: String): ClusterStoragePath = root(accountBucket).resolve(CLICKHOUSE_BACKUPS_DIR)

        /**
         * Root path for locally-produced Cassandra builds in the account bucket.
         *
         * Account-level rather than per-cluster: a build is made before any cluster exists, and is
         * installed onto whichever clusters the profile later brings up.
         */
        fun cassandraBuildsRoot(accountBucket: String): ClusterStoragePath = root(accountBucket).resolve(CASSANDRA_BUILDS_DIR)
    }

    // Core Path-like methods

    /**
     * Resolve a path segment, returning a new ClusterStoragePath.
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
     * @return A new ClusterStoragePath with the path appended
     */
    fun resolve(path: String): ClusterStoragePath {
        val newSegments = path.split("/").filter { it.isNotBlank() }
        return copy(segments = segments + newSegments)
    }

    /**
     * Get parent path, or null if this is the root.
     * Follows java.nio.file.Path.getParent() semantics.
     *
     * @return The parent path, or null if this path has no parent
     */
    fun getParent(): ClusterStoragePath? =
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
     * `bucket/key`, with no URI scheme. For logs only: a location shown to the user comes from
     * `ObjectStore.uriOf`.
     */
    override fun toString(): String {
        val key = getKey()
        return if (key.isEmpty()) bucket else "$bucket/$key"
    }

    /**
     * The object key: the store prefix followed by the segments, with an empty prefix left out.
     * Useful for SDK calls that take bucket and key separately.
     *
     * Example:
     * ```
     * val path = ClusterStoragePath.root("bucket").resolve("file.txt")
     * putRequest.bucket(path.bucket).key(path.getKey())
     * ```
     */
    fun getKey(): String = (prefix.split("/").filter { it.isNotBlank() } + segments).joinToString("/")

    // ── Technology-specific path helpers ───────────────────────────────────────

    /** Path for Cassandra data and backups. */
    fun cassandra(): ClusterStoragePath = resolve(CASSANDRA_DIR)

    /** Path for ClickHouse data. */
    fun clickhouse(): ClusterStoragePath = resolve(CLICKHOUSE_DIR)

    /** Path for Spark JARs and data. */
    fun spark(): ClusterStoragePath = resolve(SPARK_DIR)

    /** Path for EMR logs. */
    fun emrLogs(): ClusterStoragePath = resolve(SPARK_DIR).resolve(EMR_LOGS_DIR)

    /** Path for backups. */
    fun backups(): ClusterStoragePath = resolve(BACKUPS_DIR)

    /** Path for log aggregation. */
    fun logs(): ClusterStoragePath = resolve(LOGS_DIR)

    /** Path for general data storage. */
    fun data(): ClusterStoragePath = resolve(DATA_DIR)

    // ── Config file path helpers ────────────────────────────────────────────────

    /** Path for K3s kubeconfig file. */
    fun kubeconfig(): ClusterStoragePath = resolve(CONFIG_DIR).resolve(KUBECONFIG_FILE)

    /** Path for Kubernetes manifests directory. */
    fun k8s(): ClusterStoragePath = resolve(CONFIG_DIR).resolve(K8S_DIR)

    /** Path for cluster configuration files directory. */
    fun config(): ClusterStoragePath = resolve(CONFIG_DIR)

    /** Path for Cassandra patch configuration file. */
    fun cassandraPatch(): ClusterStoragePath = resolve(CONFIG_DIR).resolve(CASSANDRA_PATCH_FILE)

    /** Path for cassandra_versions.yaml file. */
    fun cassandraVersions(): ClusterStoragePath = resolve(CONFIG_DIR).resolve(CASSANDRA_VERSIONS_FILE)

    /** Path for env.sh (main environment script with SSH aliases, kubectl, etc.). */
    fun envScript(): ClusterStoragePath = resolve(CONFIG_DIR).resolve(ENV_SCRIPT_FILE)

    /** Path for environment.sh (stress environment variables). */
    fun environmentScript(): ClusterStoragePath = resolve(CONFIG_DIR).resolve(ENVIRONMENT_FILE)

    /** Path for setup_instance.sh file. */
    fun setupInstanceScript(): ClusterStoragePath = resolve(CONFIG_DIR).resolve(SETUP_INSTANCE_FILE)

    /** Path for state.json file. */
    fun stateJson(): ClusterStoragePath = resolve(CONFIG_DIR).resolve(STATE_JSON_FILE)
}
