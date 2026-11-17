package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CassandraSource
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InstalledVersion
import com.rustyrazorblade.easydblab.configuration.NodeState

/**
 * What the user asked for: a version name and the CLI options that shape its install.
 *
 * @property reinstall `cassandra install --reinstall`: the options replace the fields of a state record
 */
data class VersionRequest(
    val name: String,
    val url: String? = null,
    val branch: String? = null,
    val java: String? = null,
    val python: String? = null,
    val antFlags: String? = null,
    val reinstall: Boolean = false,
)

/** A name or an option that no source can turn into an install; nothing on any node has changed. */
class VersionResolutionException(
    message: String,
) : RuntimeException(message)

/**
 * Turns a version name and the CLI options into one [InstallSpec] for the cluster (design D1, D2,
 * D13), before any node changes, so every node installs the same build.
 *
 * Sources, in order: a record of the name on any node of the cluster, a named build in the
 * profile, `--url`, the Apache releases, the cassandra-builds `nightly` release, and the build
 * catalog. A source is read only when the ones before it do not have the name.
 */
class CassandraVersionResolver(
    private val sources: VersionSources,
    private val releaseLines: ReleaseLines = ReleaseLines.load(),
) {
    /**
     * The install of [request] for the cluster in [state]. The JDK is the cluster default; a node
     * that records its own JDK keeps it through [forNode].
     *
     * @throws VersionResolutionException when no source has the name, or `--java` is not supported
     */
    fun resolve(
        request: VersionRequest,
        state: ClusterState,
    ): InstallSpec =
        recordOf(request.name, state)?.let { fromRecord(it, request) }
            ?: sources.profileBuild(request.name)?.let { fromProfile(it, request) }
            ?: request.url?.let { fromUrl(it, request) }
            ?: fromReleaseLists(request)

    /**
     * [spec] for one node: with no `--java` or `--python`, the node keeps the JDK and Python it
     * records for the version, so `use 5.0 --java 11` stays on 11 at the next `use 5.0`.
     */
    fun forNode(
        spec: InstallSpec,
        request: VersionRequest,
        node: NodeState?,
    ): InstallSpec {
        val record = node?.installed?.get(spec.name) ?: return spec
        val java = request.java ?: record.java.takeIf { it in spec.javaSupported } ?: spec.java
        val python = request.python ?: record.python.ifBlank { spec.python }
        return spec
            .toBuilder()
            .jdks(spec.javaSupported, java)
            .python(python)
            .build()
    }

    /**
     * The install of exactly [record], with its own JDK and Python: what a replaced node gets
     * (design D7). Not a new resolution: the source and URL are the recorded ones.
     */
    fun forRecord(record: InstalledVersion): InstallSpec {
        val request = VersionRequest(record.version)
        return forNode(fromRecord(record, request), request, NodeState(installed = mutableMapOf(record.version to record)))
    }

    private fun recordOf(
        name: String,
        state: ClusterState,
    ): InstalledVersion? = state.nodes.values.firstNotNullOfOrNull { it.installed[name] }

    private fun fromRecord(
        record: InstalledVersion,
        request: VersionRequest,
    ): InstallSpec {
        val url = request.url.takeIf { request.reinstall } ?: record.url
        val asfRelease = AsfReleases.releaseOf(url).takeIf { record.source == CassandraSource.ASF }
        val defaultJava = if (record.source == CassandraSource.PROFILE) sources.profileBuild(record.version)?.java else null
        val antFlags = request.antFlags.takeIf { request.reinstall } ?: record.antFlags
        return InstallSpec
            .builder(record.version)
            .source(record.source)
            .url(url)
            .fallbackUrl(asfRelease?.let(AsfReleases::archiveUrl))
            .cacheUri(asfRelease?.let(::cacheUri))
            .jdks(record.javaSupported, chooseJava(request, record.javaSupported, defaultJava, noListHint(request.name)))
            .python(request.python ?: record.python.ifBlank { Constants.Cassandra.DEFAULT_PYTHON_VERSION })
            .branch(record.branch)
            .antFlags(antFlags.takeIf { record.branch != null })
            .build()
    }

    private fun fromProfile(
        build: ProfileBuild,
        request: VersionRequest,
    ): InstallSpec {
        val hint = "Pass --java, or add java to the entry for '${build.version}' in ${sources.profileBuildDir.path}."
        val branch = request.branch ?: build.branch
        return InstallSpec
            .builder(build.version)
            .source(CassandraSource.PROFILE)
            .url(request.url ?: build.url)
            .jdks(build.jdks().ifEmpty { listOfNotNull(request.java) }, chooseJava(request, build.jdks(), build.java, hint))
            .python(request.python ?: build.python ?: Constants.Cassandra.DEFAULT_PYTHON_VERSION)
            .branch(branch)
            .antFlags((request.antFlags ?: build.antFlags).takeIf { branch != null })
            .build()
    }

    private fun fromUrl(
        url: String,
        request: VersionRequest,
    ): InstallSpec {
        val line = releaseLines.entryFor(request.name)
        val supported = line?.javaSupported.orEmpty()
        val java = chooseJava(request, supported, null, noListHint(request.name))
        val git = request.branch != null
        return InstallSpec
            .builder(request.name)
            .source(if (git) CassandraSource.GIT else CassandraSource.URL)
            .url(url)
            .jdks(supported.ifEmpty { listOf(java) }, java)
            .python(request.python ?: Constants.Cassandra.DEFAULT_PYTHON_VERSION)
            .branch(request.branch)
            .antFlags((request.antFlags ?: line?.antFlagsFor(java)).takeIf { git })
            .build()
    }

    /** The Apache releases, then cassandra-builds, then the catalog; an unknown name names a stale ASF list. */
    private fun fromReleaseLists(request: VersionRequest): InstallSpec {
        val asf = asfLookup(request.name)
        return asf.release?.let { fromAsf(it, request) }
            ?: fromCassandraBuilds(request)
            ?: fromCatalog(request)
            ?: throw unknown(request.name, asf.list)
    }

    private fun fromAsf(
        release: String,
        request: VersionRequest,
    ): InstallSpec =
        tarballSpec(request, CassandraSource.ASF, AsfReleases.downloadUrl(release), tableJdks(request.name))
            .fallbackUrl(AsfReleases.archiveUrl(release))
            .cacheUri(cacheUri(release))
            .build()

    /** The ASF list a name was looked up in, and the release the name installs, if any. */
    private data class AsfLookup(
        val list: Cached<AsfReleases>,
        val release: String?,
    )

    /** An `X.Y.Z` the cached list lacks fetches the list one more time. */
    private fun asfLookup(name: String): AsfLookup {
        val cached = sources.asfReleases()
        val release = cached.value.resolve(name)
        if (release != null || !ASF_PATCH.matches(name)) return AsfLookup(cached, release)
        val fetched = sources.asfReleases(refresh = true)
        return AsfLookup(fetched, fetched.value.resolve(name))
    }

    private fun fromCassandraBuilds(request: VersionRequest): InstallSpec? {
        val build = sources.cassandraBuilds().value.firstOrNull { it.ref == request.name } ?: return null
        val manifestJdks = sources.cassandraBuildsManifest(build)?.javaSupported.orEmpty()
        return tarballSpec(request, CassandraSource.CASSANDRA_BUILDS, build.url, manifestJdks.ifEmpty { tableJdks(request.name) }).build()
    }

    private fun fromCatalog(request: VersionRequest): InstallSpec? {
        val build = sources.catalogBuild(request.name) ?: return null
        return tarballSpec(request, CassandraSource.BUILD_CATALOG, build.url, build.javaSupported).build()
    }

    private fun tarballSpec(
        request: VersionRequest,
        source: CassandraSource,
        url: String,
        supported: List<String>,
    ): InstallSpec.Builder {
        val java = chooseJava(request, supported, null, noListHint(request.name))
        return InstallSpec
            .builder(request.name)
            .source(source)
            .url(url)
            .jdks(supported.ifEmpty { listOf(java) }, java)
            .python(request.python ?: Constants.Cassandra.DEFAULT_PYTHON_VERSION)
    }

    private fun tableJdks(name: String): List<String> = releaseLines.entryFor(name)?.javaSupported.orEmpty()

    /**
     * `--java` when given and supported; else [defaultJava] (a profile build's `java`); else the
     * highest supported JDK. With no list, `--java` alone, or a failure that says to pass it.
     */
    private fun chooseJava(
        request: VersionRequest,
        supported: List<String>,
        defaultJava: String?,
        noListHint: String,
    ): String {
        val requested = request.java
        return when {
            requested != null && supported.isNotEmpty() && requested !in supported ->
                throw VersionResolutionException(
                    "Cassandra ${request.name} does not run on JDK $requested. Supported JDKs: ${supported.joinToString(", ")}.",
                )
            requested != null -> requested
            supported.isEmpty() -> throw VersionResolutionException("No source gives the JDKs of Cassandra ${request.name}. $noListHint")
            defaultJava != null && defaultJava in supported -> defaultJava
            else -> supported.maxBy { it.toIntOrNull() ?: 0 }
        }
    }

    private fun noListHint(name: String) = "Pass --java to choose the JDK for $name."

    private fun cacheUri(release: String): String =
        sources
            .downloadCacheRoot()
            .resolve(AsfReleases.cacheKey(release))
            .toUri()

    private fun unknown(
        name: String,
        asf: Cached<AsfReleases>,
    ): VersionResolutionException {
        val checked =
            """
            Unknown Cassandra version '$name'. Checked, in order:
              - the cluster state (no node records it)
              - the profile builds in ${sources.profileBuildDir.path}
              - the Apache releases (${AsfReleases.indexUrl()})
              - the cassandra-builds nightly release
              - the profile's build catalog
            To install a build from elsewhere, pass --url (and --branch for a git URL).
            """.trimIndent()
        val stale = "The ASF list could not be fetched; the cached copy from ${asf.fetchedAt} was used (stale).".takeIf { asf.stale }
        return VersionResolutionException(listOfNotNull(checked, stale).joinToString("\n"))
    }

    private companion object {
        val ASF_PATCH = Regex("""\d+\.\d+\.\d+""")
    }
}
