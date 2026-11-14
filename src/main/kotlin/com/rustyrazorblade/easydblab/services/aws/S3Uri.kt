package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath

/**
 * The `s3://bucket/key` form of a storage location.
 *
 * `ClusterStoragePath` is neutral and carries no scheme, so this is the one place outside the SDK
 * calls that knows how S3 spells a location. The S3 adapter renders its display URIs through
 * [uri]; an AWS-only command that takes an `s3://` argument (`spark submit`) reads it with
 * [parse].
 */
object S3Uri {
    private const val SCHEME = "s3://"

    /** Whether [value] is an `s3://` URI rather than a local path. */
    fun isS3Uri(value: String): Boolean = value.startsWith(SCHEME)

    /** [path] as `s3://bucket/key`, or `s3://bucket` for a bucket root. */
    fun uri(path: ClusterStoragePath): String = "$SCHEME$path"

    /**
     * The location an `s3://bucket/key` URI names.
     *
     * @throws IllegalArgumentException if [uri] does not start with `s3://` or names no bucket.
     */
    fun parse(uri: String): ClusterStoragePath {
        require(uri.startsWith(SCHEME)) { "S3 URI must start with '$SCHEME': $uri" }
        val segments = uri.removePrefix(SCHEME).split("/").filter { it.isNotBlank() }
        require(segments.isNotEmpty()) { "S3 URI must contain a bucket name: $uri" }
        return ClusterStoragePath.fromKey(segments.first(), segments.drop(1).joinToString("/"))
    }
}
