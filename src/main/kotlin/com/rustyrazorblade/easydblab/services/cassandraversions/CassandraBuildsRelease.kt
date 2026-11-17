package com.rustyrazorblade.easydblab.services.cassandraversions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One branch build of the cassandra-builds `nightly` release: the tarball
 * `apache-cassandra-<ref>-bin.tar.gz`, and the manifest beside it when the release has one.
 */
@Serializable
data class CassandraBuild(
    val ref: String,
    val url: String,
    val manifestUrl: String? = null,
)

/** The JDK fields of a cassandra-builds manifest (cassandra-builds#1). */
data class CassandraBuildsManifest(
    val javaSupported: List<String>,
    val javaDefault: String?,
)

/**
 * Parses the GitHub API answer for the cassandra-builds `nightly` release and the manifests beside
 * its tarballs. The manifest format is cassandra-builds's, so the JDK fields are read leniently: a
 * list or a comma string, of strings or numbers, with unknown fields ignored.
 */
object CassandraBuildsRelease {
    private const val PREFIX = "apache-cassandra-"
    private const val TARBALL_SUFFIX = "-bin.tar.gz"
    private const val MANIFEST_SUFFIX = "-bin.json"
    private val JSON = Json { ignoreUnknownKeys = true }

    /** The builds of the release, one per tarball asset. */
    fun parse(releaseJson: String): List<CassandraBuild> {
        val release = JSON.parseToJsonElement(releaseJson).jsonObject
        val assets =
            requireNotNull(release["assets"] as? JsonArray) { "The GitHub release answer has no assets list" }
                .map { it.jsonObject }
                .mapNotNull { asset ->
                    val name = asset["name"]?.jsonPrimitive?.contentOrNull
                    val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    if (name != null && url != null) name to url else null
                }.toMap()
        return assets
            .mapNotNull { (name, url) -> refOf(name, TARBALL_SUFFIX)?.let { ref -> ref to url } }
            .map { (ref, url) -> CassandraBuild(ref, url, assets["$PREFIX$ref$MANIFEST_SUFFIX"]) }
    }

    /** The JDK fields of a manifest. */
    fun parseManifest(manifestJson: String): CassandraBuildsManifest {
        val manifest = JSON.parseToJsonElement(manifestJson).jsonObject
        return CassandraBuildsManifest(
            javaSupported = jdkList(manifest["java_supported"]),
            javaDefault = (manifest["java_default"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null },
        )
    }

    private fun jdkList(element: JsonElement?): List<String> {
        val raw =
            when (element) {
                is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> element.contentOrNull?.split(",").orEmpty()
                else -> emptyList()
            }
        return raw.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun refOf(
        assetName: String,
        suffix: String,
    ): String? = assetName.takeIf { it.startsWith(PREFIX) && it.endsWith(suffix) }?.removePrefix(PREFIX)?.removeSuffix(suffix)
}
