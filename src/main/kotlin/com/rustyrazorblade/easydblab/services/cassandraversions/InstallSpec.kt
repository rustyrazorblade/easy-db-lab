package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.configuration.CassandraSource
import com.rustyrazorblade.easydblab.configuration.InstalledVersion
import com.rustyrazorblade.easydblab.ssh.redactUrlCredentials

/**
 * Everything a node needs to install one Cassandra version, resolved by the CLI before any node
 * changes: the node script takes these as explicit arguments and looks nothing up.
 *
 * @property url the tarball, `s3://` or git URL, as given; it may carry a credential, so pass it as
 *   a secret and record [recordedUrl]
 * @property fallbackUrl the second URL to try (the ASF archive), or null
 * @property cacheUri the download-cache object for the tarball (ASF releases only), or null
 * @property branch the branch of a git build; null for a tarball
 */
class InstallSpec private constructor(
    builder: Builder,
) {
    val name: String = builder.name
    val source: CassandraSource = checkNotNull(builder.source) { "InstallSpec for ${builder.name} has no source" }
    val url: String = builder.url
    val fallbackUrl: String? = builder.fallbackUrl
    val cacheUri: String? = builder.cacheUri
    val javaSupported: List<String> = builder.javaSupported
    val java: String = builder.java
    val python: String = builder.python
    val branch: String? = builder.branch
    val antFlags: String? = builder.antFlags

    init {
        require(name.isNotBlank()) { "InstallSpec has no name" }
        require(url.isNotBlank()) { "InstallSpec for $name has no url" }
        require(java in javaSupported) { "InstallSpec for $name chose JDK $java outside ${javaSupported.joinToString(", ")}" }
    }

    /** True when the node clones [url] and builds [branch] with ant. */
    val isGitBuild: Boolean get() = branch != null

    /** [url] with any userinfo credential replaced, for the state record, events and errors. */
    val recordedUrl: String get() = redactUrlCredentials(url)

    /** Every URL the node may try, redacted, for a failure message. */
    val urlsTried: List<String> get() = listOfNotNull(recordedUrl, fallbackUrl?.let(::redactUrlCredentials))

    /** The record of this version for the node's `installed` map. */
    fun toRecord(): InstalledVersion =
        InstalledVersion(
            version = name,
            java = java,
            javaSupported = javaSupported,
            python = python,
            source = source,
            url = recordedUrl,
            branch = branch,
            antFlags = antFlags,
        )

    /** A builder pre-filled with this spec, for a variant (another JDK, a replaced URL). */
    fun toBuilder(): Builder =
        Builder(name)
            .source(source)
            .url(url)
            .fallbackUrl(fallbackUrl)
            .cacheUri(cacheUri)
            .jdks(javaSupported, java)
            .python(python)
            .branch(branch)
            .antFlags(antFlags)

    override fun toString(): String = "InstallSpec($name, $source, $recordedUrl, java=$java of $javaSupported, python=$python)"

    /** Builds an [InstallSpec]; [build] checks that the chosen JDK is in the supported list. */
    class Builder(
        internal val name: String,
    ) {
        internal var source: CassandraSource? = null
        internal var url: String = ""
        internal var fallbackUrl: String? = null
        internal var cacheUri: String? = null
        internal var javaSupported: List<String> = emptyList()
        internal var java: String = ""
        internal var python: String = ""
        internal var branch: String? = null
        internal var antFlags: String? = null

        fun source(value: CassandraSource) = apply { source = value }

        fun url(value: String) = apply { url = value }

        fun fallbackUrl(value: String?) = apply { fallbackUrl = value }

        fun cacheUri(value: String?) = apply { cacheUri = value }

        fun jdks(
            supported: List<String>,
            chosen: String,
        ) = apply {
            javaSupported = supported
            java = chosen
        }

        fun python(value: String) = apply { python = value }

        fun branch(value: String?) = apply { branch = value?.ifBlank { null } }

        fun antFlags(value: String?) = apply { antFlags = value?.ifBlank { null } }

        fun build(): InstallSpec = InstallSpec(this)
    }

    companion object {
        fun builder(name: String): Builder = Builder(name)
    }
}
