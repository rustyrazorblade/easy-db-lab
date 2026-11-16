package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CassandraBuildManifest
import com.rustyrazorblade.easydblab.configuration.CassandraVersion
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * The set of locally-produced Cassandra builds published to the profile's account bucket.
 *
 * Builds live in S3 rather than in a local yaml so they belong to the profile, not to the laptop
 * that produced them: a build made on a desktop installs from a laptop, and survives a machine
 * being wiped. The bucket is the only record — there is no index object to drift out of sync with
 * what is actually stored, so a half-finished upload shows up as a missing build rather than a
 * listed build that cannot be installed.
 */
class CassandraBuildCatalog(
    private val objectStore: ObjectStore,
    private val bucketService: AwsS3BucketService,
    private val userConfig: User,
) {
    private val log = KotlinLogging.logger {}

    /** Uploads the tarball and its manifest, and returns the build's S3 directory. */
    fun publish(
        manifest: CassandraBuildManifest,
        tarball: File,
    ): ClusterS3Path {
        val dir = buildDir(manifest.name)

        // The tarball goes up first. A manifest with no tarball beside it would list a build that
        // cannot be installed; a tarball with no manifest is simply not listed.
        objectStore.uploadFile(tarball, dir.resolve(manifest.tarball), showProgress = true)
        objectStore.uploadContent(manifest.encode(), dir.resolve(CassandraBuildManifest.FILE_NAME))
        return dir
    }

    /** Every published build, newest first. */
    fun list(): List<CassandraBuildManifest> =
        objectStore
            .listFiles(root(), recursive = true)
            .filter { it.path.getFileName() == CassandraBuildManifest.FILE_NAME }
            .mapNotNull { info ->
                // One unreadable manifest must not blind the whole listing — a build half-written
                // by an interrupted upload would otherwise make every other build invisible.
                runCatching { CassandraBuildManifest.parse(objectStore.readContent(info.path)) }
                    .onFailure { log.warn(it) { "Ignoring unreadable build manifest at ${info.path}" } }
                    .getOrNull()
            }.sortedByDescending { it.builtAt }

    fun find(name: String): CassandraBuildManifest? {
        val path = buildDir(name).resolve(CassandraBuildManifest.FILE_NAME)
        if (!objectStore.fileExists(path)) return null
        return CassandraBuildManifest.parse(objectStore.readContent(path))
    }

    /**
     * The build expressed as an installable version.
     *
     * The url is an `s3://` URI, not a presigned https one: nodes reach the account bucket with
     * their instance profile, and a presigned URL's query string would stop
     * `install-cassandra-version` recognising the tarball at all — it selects that mode on the URL
     * ending in `.tar.gz`.
     */
    fun asVersion(manifest: CassandraBuildManifest): CassandraVersion =
        CassandraVersion(
            version = manifest.name,
            java = manifest.javaVersion,
            python = Constants.Cassandra.DEFAULT_PYTHON_VERSION,
            jvmOptions = null,
            antFlags = null,
            url = tarballUri(manifest),
        )

    fun tarballUri(manifest: CassandraBuildManifest): String = buildDir(manifest.name).resolve(manifest.tarball).toUri()

    private fun buildDir(name: String): ClusterS3Path = root().resolve(name)

    /**
     * Ensures the account bucket exists before handing back its builds prefix, so `cassandra build`
     * works on a profile that has never provisioned a cluster.
     */
    private fun root(): ClusterS3Path = ClusterS3Path.cassandraBuildsRoot(bucketService.ensureAccountBucket(userConfig))
}
