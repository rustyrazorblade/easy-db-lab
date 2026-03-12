package com.rustyrazorblade.easydblab.configuration

/**
 * Immutable S3 path abstraction following java.nio.file.Path patterns.
 * Provides type-safe S3 path construction for account-level buckets with cluster prefixes.
 *
 * All clusters within an account share a single S3 bucket, with each cluster's data
 * isolated under a cluster-specific prefix (clusters/<name>-<id>/). Within each cluster
 * prefix, paths are organized by technology subdirectories (cassandra/, clickhouse/, spark/).
 *
 * Example usage:
 * ```
 * val s3Path = ClusterS3Path.from(clusterState)
 * val jarPath = s3Path.spark().resolve("myapp.jar")
 * println(jarPath) // s3://my-account-bucket/clusters/mycluster-abc123/spark/myapp.jar
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
        internal const val CASSANDRA_CONFIG_DIR = "cassandra-config"
        internal const val CASSANDRA_VERSIONS_FILE = "cassandra_versions.yaml"
        internal const val ENV_SCRIPT_FILE = "env.sh"
        internal const val ENVIRONMENT_FILE = "environment.sh"
        internal const val SETUP_INSTANCE_FILE = "setup_instance.sh"
        internal const val TEMPO_DIR = "tempo"
        internal const val PYROSCOPE_DIR = "pyroscope"
        internal const val VICTORIA_METRICS_DIR = "victoriametrics"
        internal const val VICTORIA_LOGS_DIR = "victorialogs"

        /**
         * Create a ClusterS3Path from ClusterState.
         * Uses the account-level bucket and cluster prefix from ClusterState.
         *
         * @param clusterState The cluster state containing s3Bucket
         * @return A new ClusterS3Path for this cluster's prefix within the account bucket
         * @throws IllegalStateException if s3Bucket is not configured
         */
        fun from(clusterState: ClusterState): ClusterS3Path {
            val bucket =
                clusterState.s3Bucket
                    ?: error("S3 bucket not configured for cluster '${clusterState.name}'. Run 'easy-db-lab up' first.")
            return ClusterS3Path(bucket, clusterState.clusterPrefix().split("/"))
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

        /**
         * Parse an S3 URI (s3://bucket/key) into a ClusterS3Path.
         *
         * @param uri The S3 URI to parse (e.g., "s3://my-bucket/path/to/file.jar")
         * @return A new ClusterS3Path representing the URI
         * @throws IllegalArgumentException if the URI doesn't start with "s3://"
         */
        fun fromUri(uri: String): ClusterS3Path {
            require(uri.startsWith("s3://")) { "S3 URI must start with 's3://': $uri" }
            val withoutScheme = uri.removePrefix("s3://")
            val segments = withoutScheme.split("/").filter { it.isNotBlank() }
            require(segments.isNotEmpty()) { "S3 URI must contain a bucket name: $uri" }
            val bucket = segments.first()
            val keySegments = segments.drop(1)
            return ClusterS3Path(bucket, keySegments)
        }
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
     * Returns the HTTPS endpoint URL for this S3 path.
     * Used by services like ClickHouse that need an HTTP endpoint rather than an s3:// URI.
     *
     * @param region AWS region for the S3 bucket
     * @return The HTTPS endpoint URL (e.g. https://bucket.s3.us-west-2.amazonaws.com/clusters/name-id/clickhouse/)
     */
    fun toEndpointUrl(region: String): String {
        val key = getKey()
        return if (key.isEmpty()) {
            "https://$bucket.s3.$region.amazonaws.com/"
        } else {
            "https://$bucket.s3.$region.amazonaws.com/$key/"
        }
    }

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
}
