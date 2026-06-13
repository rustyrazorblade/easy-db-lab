package com.rustyrazorblade.easydblab.services

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * A single extension alias entry in the postgres extensions registry.
 * Aliases map a short name (e.g. "duckdb") to the CNPG image and PostgreSQL config
 * required to activate that extension.
 *
 * `__PG_MAJOR__` in [image] is substituted at resolve time with the installed postgres major version.
 */
@Serializable
data class ExtensionAlias(
    val description: String,
    val image: String,
    @SerialName("shared_preload_libraries")
    val sharedPreloadLibraries: List<String> = emptyList(),
    @SerialName("create_extensions")
    val createExtensions: List<String> = emptyList(),
    @SerialName("postgres_uid")
    val postgresUid: Int = 26,
    @SerialName("postgres_gid")
    val postgresGid: Int = 26,
    @SerialName("postgres_port")
    val postgresPort: Int = 30432,
    @SerialName("metrics_port")
    val metricsPort: Int = 30987,
)

@Serializable
private data class ExtensionsFile(
    val extensions: Map<String, ExtensionAlias>,
)

private val extensionYaml = Yaml.default

/**
 * Registry of known postgres extension aliases loaded from `extensions.yaml`.
 *
 * Provides lookup by alias name and enumeration for the `postgres extensions` subcommand.
 * Built-in aliases live in the classpath; after install they are copied to the kit directory
 * where [fromFile] loads them at runtime.
 */
class ExtensionRegistry private constructor(
    private val aliases: Map<String, ExtensionAlias>,
) {
    fun lookup(name: String): ExtensionAlias? = aliases[name]

    fun all(): Map<String, ExtensionAlias> = aliases

    companion object {
        fun fromFile(file: File): ExtensionRegistry {
            if (!file.isFile) return ExtensionRegistry(emptyMap())
            val parsed = extensionYaml.decodeFromString(ExtensionsFile.serializer(), file.readText())
            return ExtensionRegistry(parsed.extensions)
        }

        fun fromString(content: String): ExtensionRegistry {
            val parsed = extensionYaml.decodeFromString(ExtensionsFile.serializer(), content)
            return ExtensionRegistry(parsed.extensions)
        }

        fun fromClasspath(kitName: String = "postgres"): ExtensionRegistry {
            val resourcePath = "com/rustyrazorblade/easydblab/kits/$kitName/extensions.yaml"
            val content =
                ExtensionRegistry::class.java.classLoader
                    .getResourceAsStream(resourcePath)
                    ?.bufferedReader()
                    ?.readText()
                    ?: return ExtensionRegistry(emptyMap())
            return fromString(content)
        }

        fun empty(): ExtensionRegistry = ExtensionRegistry(emptyMap())
    }
}

/**
 * Resolved extension configuration produced by [ExtensionResolver].
 *
 * Contains the final image name (with `__PG_MAJOR__` substituted) and the combined
 * set of preload libraries and `CREATE EXTENSION` statements from all activated aliases
 * plus any escape-hatch flags.
 */
data class ExtensionConfig(
    val image: String,
    val sharedPreloadLibraries: List<String>,
    val createExtensions: List<String>,
    val postgresUid: Int = 26,
    val postgresGid: Int = 26,
    val postgresPort: Int = 30432,
    val metricsPort: Int = 30987,
)

/**
 * Resolves postgres extension flags into a concrete [ExtensionConfig].
 *
 * Merges alias entries from the registry with escape-hatch flags (`--image`,
 * `--shared-preload-libraries`, `--create-extension`). Detects image conflicts when
 * multiple aliases each define different images and no `--image` override is provided.
 */
class ExtensionResolver(
    private val registry: ExtensionRegistry,
    private val postgresVersion: String,
) {
    /**
     * Resolves all extension inputs into an [ExtensionConfig].
     *
     * @param extensions alias names from `--extension` flags
     * @param imageOverride explicit image from `--image` flag, overrides any alias image
     * @param additionalPreload extra libraries from `--shared-preload-libraries` flags
     * @param additionalCreate extra extension names from `--create-extension` flags
     */
    fun resolve(
        extensions: List<String>,
        imageOverride: String?,
        additionalPreload: List<String>,
        additionalCreate: List<String>,
    ): ExtensionConfig {
        val aliases =
            extensions.map { name ->
                registry.lookup(name) ?: error("Unknown extension alias: '$name'. Run 'postgres extensions' to see available aliases.")
            }

        val resolvedImage =
            when {
                imageOverride != null -> imageOverride
                aliases.isEmpty() -> "ghcr.io/cloudnative-pg/postgresql:$postgresVersion"
                else -> {
                    val distinctImages = aliases.map { it.image }.distinct()
                    if (distinctImages.size > 1) {
                        val conflicting = extensions.zip(aliases).joinToString(", ") { (name, alias) -> "$name (${alias.image})" }
                        error(
                            "Multiple extensions define different images: $conflicting. " +
                                "Build a combined image and pass it via --image.",
                        )
                    }
                    distinctImages.first()
                }
            }

        val pgMajor = postgresVersion.substringBefore('.')
        val finalImage = resolvedImage.replace("__PG_MAJOR__", pgMajor)

        val preload = (aliases.flatMap { it.sharedPreloadLibraries } + additionalPreload).distinct()
        val create = (aliases.flatMap { it.createExtensions } + additionalCreate).distinct()
        val uid = aliases.firstOrNull()?.postgresUid ?: 26
        val gid = aliases.firstOrNull()?.postgresGid ?: 26
        val postgresPort = aliases.firstOrNull()?.postgresPort ?: 30432
        val metricsPort = aliases.firstOrNull()?.metricsPort ?: 30987

        return ExtensionConfig(
            image = finalImage,
            sharedPreloadLibraries = preload,
            createExtensions = create,
            postgresUid = uid,
            postgresGid = gid,
            postgresPort = postgresPort,
            metricsPort = metricsPort,
        )
    }
}
