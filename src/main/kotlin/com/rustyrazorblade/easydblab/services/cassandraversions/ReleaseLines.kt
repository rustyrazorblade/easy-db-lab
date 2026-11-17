package com.rustyrazorblade.easydblab.services.cassandraversions

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One release line of the table: the JDKs every version of the line runs on, and the ant flags a
 * git build of the line needs on a given JDK.
 */
@Serializable
data class ReleaseLine(
    val name: String,
    @SerialName("java_supported") val javaSupported: List<String>,
    @SerialName("ant_flags") val antFlags: Map<String, String> = emptyMap(),
    @SerialName("build_java") val buildJava: String? = null,
) {
    init {
        require(javaSupported.isNotEmpty()) { "Release line $name lists no JDK" }
        require(buildJava == null || buildJava in javaSupported) {
            "Release line $name builds on JDK $buildJava, which it does not support"
        }
        val unknown = antFlags.keys - javaSupported.toSet()
        require(unknown.isEmpty()) {
            "Release line $name has ant flags for JDK ${unknown.joinToString(", ")}, which it does not support"
        }
    }

    /** The ant flags of a git build of this line on [java], or null when it needs none. */
    fun antFlagsFor(java: String): String? = antFlags[java]

    /**
     * The JDKs a cassandra-builds nightly of this line runs on: the ones from its build JDK up,
     * because a class file compiled on JDK N does not load on a lower one.
     */
    fun nightlyJdks(): List<String> {
        val floor = buildJava?.toInt() ?: return javaSupported
        return javaSupported.filter { it.toInt() >= floor }
    }
}

/**
 * The release-line table, the last layer of the JDK choice: it gives a JDK list to a version whose
 * own source (profile entry, build manifest) has none. It ships as a classpath resource, so it works
 * without a source checkout.
 */
@Serializable
data class ReleaseLines(
    val lines: List<ReleaseLine>,
) {
    /**
     * The entry for [version]: the longest entry name that is a prefix of it, ending at a `-` or at
     * the end of the name; else the entry of its leading `X.Y`; else null.
     */
    fun entryFor(version: String): ReleaseLine? =
        lines
            .filter { version == it.name || version.startsWith("${it.name}-") }
            .maxByOrNull { it.name.length }
            ?: leadingReleaseLine(version)?.let { line -> lines.firstOrNull { it.name == line } }

    companion object {
        private const val RESOURCE = "/com/rustyrazorblade/easydblab/cassandra/release-lines.yaml"
        private val LEADING_X_Y = Regex("""^(\d+\.\d+)(?:[.\-].*)?$""")

        /** Loads the table shipped with the CLI. */
        fun load(): ReleaseLines {
            val text =
                checkNotNull(ReleaseLines::class.java.getResource(RESOURCE)) { "Missing resource $RESOURCE" }.readText()
            return parse(text)
        }

        /** Parses a table in the resource's format. */
        fun parse(yaml: String): ReleaseLines = Yaml.default.decodeFromString(serializer(), yaml)

        /** The `X.Y` that [version] starts with (`5.0-rustyrazorblade-HEAD` is `5.0`), or null. */
        fun leadingReleaseLine(version: String): String? = LEADING_X_Y.matchEntire(version)?.groupValues?.get(1)
    }
}
