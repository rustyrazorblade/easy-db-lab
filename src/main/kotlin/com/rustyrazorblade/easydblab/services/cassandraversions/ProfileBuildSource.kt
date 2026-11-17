package com.rustyrazorblade.easydblab.services.cassandraversions

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * A named build in the profile: the owner's chosen name for a tarball or a git branch, which every
 * cluster of the profile can install with no other arguments.
 *
 * @property version the name, and the install directory `/usr/local/cassandra/<version>`
 * @property url a tarball URL, an `s3://` URI, or a git URL (with [branch])
 * @property java the default JDK; one of [javaSupported] when both are set
 */
@Serializable
data class ProfileBuild(
    val version: String,
    val url: String = "",
    val branch: String? = null,
    @SerialName("java_supported") val javaSupported: List<String> = emptyList(),
    val java: String? = null,
    val python: String? = null,
    @SerialName("ant_flags") val antFlags: String? = null,
) {
    /** The JDKs this build runs on: [javaSupported], else [java] alone, else none. */
    fun jdks(): List<String> = javaSupported.ifEmpty { listOfNotNull(java) }
}

/** A profile build file that cannot be used; the message names the file. */
class ProfileBuildException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Reads the named builds of the profile's `cassandra_versions/` directory ([dir]): every `*.yaml`
 * file in it holds a list of [ProfileBuild] entries. Read from the profile at resolve time, so it
 * works for an install with no source checkout.
 */
class ProfileBuildSource(
    private val dir: File,
) {
    /**
     * Every build, in file then entry order.
     *
     * @throws ProfileBuildException for a file that cannot be parsed, an entry with no `url`, a
     *   `java` not in `java_supported`, or a name declared in two files
     */
    fun builds(): List<ProfileBuild> {
        val loaded = yamlFiles().flatMap { file -> read(file).map { file to it } }
        val duplicate = loaded.groupBy { it.second.version }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            throw ProfileBuildException(
                "Cassandra build '${duplicate.key}' is declared more than once, in " +
                    duplicate.value.joinToString(", ") { it.first.path },
            )
        }
        return loaded.map { it.second }
    }

    /** The build named [name], or null. */
    fun find(name: String): ProfileBuild? = builds().firstOrNull { it.version == name }

    private fun yamlFiles(): List<File> =
        dir
            .listFiles { file -> file.isFile && file.extension in YAML_EXTENSIONS }
            .orEmpty()
            .sortedBy { it.name }

    private fun read(file: File): List<ProfileBuild> {
        val entries =
            try {
                YAML.decodeFromString(ListSerializer(ProfileBuild.serializer()), file.readText())
            } catch (e: SerializationException) {
                throw ProfileBuildException("Cannot read the Cassandra builds in ${file.path}: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw ProfileBuildException("Cannot read the Cassandra builds in ${file.path}: ${e.message}", e)
            }
        entries.forEach { validate(file, it) }
        return entries
    }

    private fun validate(
        file: File,
        build: ProfileBuild,
    ) {
        if (build.url.isBlank()) {
            throw ProfileBuildException(
                "Cassandra build '${build.version}' in ${file.path} has no url. Every entry needs a url " +
                    "(a tarball, an s3:// URI or a git URL); an Apache release needs no entry.",
            )
        }
        if (build.java != null && build.javaSupported.isNotEmpty() && build.java !in build.javaSupported) {
            throw ProfileBuildException(
                "Cassandra build '${build.version}' in ${file.path} has java ${build.java}, " +
                    "which is not in its java_supported (${build.javaSupported.joinToString(", ")})",
            )
        }
    }

    private companion object {
        val YAML_EXTENSIONS = setOf("yaml", "yml")
        val YAML = Yaml(configuration = YamlConfiguration(strictMode = false))
    }
}
