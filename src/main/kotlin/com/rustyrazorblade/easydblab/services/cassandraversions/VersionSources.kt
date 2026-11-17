package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.services.CassandraBuildCatalog
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.IOException

private val log = KotlinLogging.logger {}

/**
 * A build of the profile's build catalog, as the resolver needs it: where it is, the JDKs it runs
 * on, and [javaDefault], the JDK it was built with, which it runs on when no `--java` is given.
 */
data class CatalogBuild(
    val url: String,
    val javaSupported: List<String>,
    val javaDefault: String? = null,
)

/**
 * The version sources the resolver reads, other than the cluster state. A port, so the resolver's
 * rules are tested against fixed lists, and so the account bucket and the public hosts are reached
 * only when a rule needs them.
 */
interface VersionSources {
    /** Where the profile builds are read from, named in an error. */
    val profileBuildDir: File

    /** The profile build named [name], or null. */
    fun profileBuild(name: String): ProfileBuild?

    /** Every profile build. */
    fun profileBuilds(): List<ProfileBuild>

    /** The Apache releases; [refresh] fetches the list again. */
    fun asfReleases(refresh: Boolean = false): Cached<AsfReleases>

    /** The builds of the cassandra-builds `nightly` release; [refresh] fetches the list again. */
    fun cassandraBuilds(refresh: Boolean = false): Cached<List<CassandraBuild>>

    /** The manifest beside [build], or null when the release has none for it. */
    fun cassandraBuildsManifest(build: CassandraBuild): CassandraBuildsManifest?

    /** The build catalog entry named [name], or null. */
    fun catalogBuild(name: String): CatalogBuild?

    /** The names of the builds in the build catalog. */
    fun catalogBuildNames(): List<String>

    /** The account's download cache. */
    fun downloadCacheRoot(): ClusterS3Path
}

/**
 * The real [VersionSources]: the profile directory, the public ASF and cassandra-builds lists
 * cached in the profile, and the build catalog in the account bucket.
 */
class DefaultVersionSources(
    profileDir: File,
    override val profileBuildDir: File,
    private val catalog: CassandraBuildCatalog,
    private val bucketService: AwsS3BucketService,
    private val user: User,
    private val http: PublicHttp = PublicHttp(),
) : VersionSources {
    private val profileBuildSource = ProfileBuildSource(profileBuildDir)

    private val asfCache =
        VersionSourceCache(
            file = File(profileDir, "${Constants.Cassandra.SOURCE_CACHE_DIR}/asf.json"),
            sourceName = "Apache release",
            url = AsfReleases.indexUrl(),
            serializer = AsfReleases.serializer(),
        )

    private val cassandraBuildsCache =
        VersionSourceCache(
            file = File(profileDir, "${Constants.Cassandra.SOURCE_CACHE_DIR}/cassandra-builds.json"),
            sourceName = "cassandra-builds",
            url = Constants.Cassandra.CASSANDRA_BUILDS_RELEASE_API,
            serializer = ListSerializer(CassandraBuild.serializer()),
        )

    override fun profileBuild(name: String): ProfileBuild? = profileBuildSource.find(name)

    override fun profileBuilds(): List<ProfileBuild> = profileBuildSource.builds()

    override fun asfReleases(refresh: Boolean): Cached<AsfReleases> =
        asfCache.get(refresh) { AsfReleases.parseIndex(fetchRequired(AsfReleases.indexUrl())) }

    override fun cassandraBuilds(refresh: Boolean): Cached<List<CassandraBuild>> =
        cassandraBuildsCache.get(refresh) {
            CassandraBuildsRelease.parse(fetchRequired(Constants.Cassandra.CASSANDRA_BUILDS_RELEASE_API))
        }

    /**
     * The manifest is optional: when it cannot be fetched after the retries, the build has none,
     * and the release-line table gives its JDKs.
     */
    override fun cassandraBuildsManifest(build: CassandraBuild): CassandraBuildsManifest? =
        build.manifestUrl?.let(::fetchOptional)?.let(CassandraBuildsRelease::parseManifest)

    override fun catalogBuild(name: String): CatalogBuild? =
        catalog.find(name)?.let { CatalogBuild(catalog.tarballUri(it), it.supportedJdks(), it.javaVersion) }

    override fun catalogBuildNames(): List<String> = catalog.list().map { it.name }

    override fun downloadCacheRoot(): ClusterS3Path = ClusterS3Path.downloadCacheRoot(bucketService.ensureAccountBucket(user))

    private fun fetchOptional(url: String): String? =
        try {
            http.getOrNull(url)
        } catch (e: IOException) {
            log.warn(e) { "Reading the manifest $url failed; using the release-line table" }
            null
        } catch (e: TransientHttpStatusException) {
            log.warn(e) { "Reading the manifest $url failed; using the release-line table" }
            null
        }

    private fun fetchRequired(url: String): String = http.getOrNull(url) ?: throw HttpStatusException(url, Constants.HttpStatus.NOT_FOUND)
}
