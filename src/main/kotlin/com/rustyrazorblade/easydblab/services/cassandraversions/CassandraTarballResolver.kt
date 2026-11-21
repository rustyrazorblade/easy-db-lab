package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CassandraSource
import com.rustyrazorblade.easydblab.configuration.ClusterState
import java.time.Duration

/**
 * Turns the cassandra-pods kit's `--version` or `--build` into the [TarballArtifact] its pods run.
 *
 * `--version` goes through [CassandraVersionResolver], the resolver `cassandra use` uses, so a name
 * means the same build on both paths. `--build` reads cassandra-builds alone, so `5.0` (an ASF line)
 * and `5.0-HEAD` (a nightly) can never be confused. Either way the sha256 comes from the source
 * ([TarballDigests]), and a catalog build gets a presigned URL, because a pod off AWS has no
 * instance profile.
 *
 * Every failure is a [VersionResolutionException] naming its source, raised before any resource exists.
 */
class CassandraTarballResolver(
    private val versions: CassandraVersionResolver,
    private val sources: VersionSources,
    private val digests: TarballDigests,
    private val presignTtl: Duration = Constants.Kit.CassandraPods.PRESIGN_TTL,
    private val releaseLines: ReleaseLines = ReleaseLines.load(),
) {
    /** The tarball `--version` [name] runs, on [java] or the version's default JDK. */
    fun resolveVersion(
        name: String,
        java: String?,
        state: ClusterState,
    ): TarballArtifact {
        val spec = versions.resolve(VersionRequest(name = name, java = java), state)
        return when (spec.source) {
            CassandraSource.ASF -> asf(spec)
            CassandraSource.CASSANDRA_BUILDS -> resolveBuild(name, java)
            CassandraSource.BUILD_CATALOG -> catalog(spec)
            else ->
                throw VersionResolutionException(
                    "Cassandra $name resolves to a ${spec.source} build (${spec.recordedUrl}), which the cassandra-pods kit cannot run: " +
                        "it runs Apache releases, cassandra-builds builds (--build) and catalog builds, or any image with --image.",
                )
        }
    }

    /** The tarball of the cassandra-builds build [ref], read from cassandra-builds alone. */
    fun resolveBuild(
        ref: String,
        java: String?,
    ): TarballArtifact {
        val build =
            sources.cassandraBuilds().value.firstOrNull { it.ref == ref }
                ?: throw VersionResolutionException(
                    "cassandra-builds has no build named '$ref' (--build reads only the cassandra-builds nightly release). " +
                        "See the builds with: easy-db-lab cassandra list",
                )
        val supported =
            sources.cassandraBuildsManifest(build)?.javaSupported.orEmpty().ifEmpty {
                releaseLines.entryFor(ref)?.nightlyJdks().orEmpty()
            }
        return TarballArtifact(
            url = build.url,
            fallbackUrl = null,
            sha256 = TarballDigests.github(build.digest, build.url),
            build = ref,
            java = chooseJava(ref, java, supported),
        )
    }

    private fun asf(spec: InstallSpec): TarballArtifact {
        val release =
            checkNotNull(AsfReleases.releaseOf(spec.url)) { "The ASF install of ${spec.name} has no release in its URL ${spec.url}" }
        return TarballArtifact(
            url = AsfReleases.downloadUrl(release),
            fallbackUrl = AsfReleases.archiveUrl(release),
            sha256 = digests.asf(release),
            build = release,
            java = spec.java,
        )
    }

    private fun catalog(spec: InstallSpec): TarballArtifact {
        val build = checkNotNull(sources.catalogBuild(spec.name)) { "The catalog build ${spec.name} vanished while resolving" }
        val sha256 = TarballDigests.catalog(build.tarballSha256, spec.name)
        return TarballArtifact(
            url = sources.presignCatalogTarball(spec.name, presignTtl),
            fallbackUrl = null,
            sha256 = sha256,
            build = spec.name,
            java = spec.java,
        )
    }

    /** `--java` when the build runs on it; else the highest JDK it runs on. */
    private fun chooseJava(
        ref: String,
        requested: String?,
        supported: List<String>,
    ): String =
        when {
            requested != null && supported.isNotEmpty() && requested !in supported ->
                throw VersionResolutionException(
                    "The cassandra-builds build $ref does not run on JDK $requested. Supported JDKs: ${supported.joinToString(", ")}.",
                )
            requested != null -> requested
            supported.isEmpty() ->
                throw VersionResolutionException("No source gives the JDKs of the cassandra-builds build $ref. Pass --java.")
            else -> supported.maxBy { it.toIntOrNull() ?: 0 }
        }
}
