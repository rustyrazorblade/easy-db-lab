package com.rustyrazorblade.easydblab.services

import com.charleskorn.kaml.Yaml
import com.rustyrazorblade.easydblab.Context
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * A named additional kit source directory, analogous to a git remote.
 *
 * [name] is a user-chosen label (e.g. "myproject"); [path] is the canonical
 * absolute path to a parent directory whose subdirectories are individual kit templates.
 */
@Serializable
data class KitSourceEntry(
    val name: String,
    val path: String,
)

/**
 * On-disk representation of user-registered additional kit source directories.
 *
 * Each source has a name and a path, stored as kit-sources.yaml in the active profile directory.
 */
@Serializable
data class KitSourcesConfig(
    val sources: List<KitSourceEntry> = emptyList(),
)

/** Result of [KitSourcesProvider.add]. */
enum class AddSourceResult { ADDED, UPDATED }

/**
 * Manages persistence of user-registered additional kit source directories.
 *
 * Reads and writes kit-sources.yaml in the active profile directory using
 * kotlinx.serialization. An absent file is treated as an empty source list.
 */
class KitSourcesProvider(
    context: Context,
) {
    private val log = KotlinLogging.logger {}
    private val configFile = File(context.profileDir, "kit-sources.yaml")
    private val yaml = Yaml.default

    /** Loads registered kit sources. Returns empty config if the file is absent. */
    fun load(): KitSourcesConfig {
        if (!configFile.exists()) return KitSourcesConfig()
        return try {
            yaml.decodeFromString(KitSourcesConfig.serializer(), configFile.readText())
        } catch (e: Exception) {
            log.warn { "Failed to read $configFile: ${e.message}. Using empty kit sources." }
            println("Warning: could not read kit-sources.yaml: ${e.message}")
            KitSourcesConfig()
        }
    }

    /** Saves the config to kit-sources.yaml atomically via a temp-file rename. */
    fun save(config: KitSourcesConfig) {
        val tmp = File(configFile.parent, "${configFile.name}.tmp")
        tmp.writeText(yaml.encodeToString(config))
        Files.move(tmp.toPath(), configFile.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
    }

    /**
     * Registers [name] pointing at [path]. If [name] already exists its path is updated.
     * The path is stored in canonical form to deduplicate symlinks.
     * Returns [AddSourceResult.ADDED] if new, [AddSourceResult.UPDATED] if the name existed.
     */
    fun add(
        name: String,
        path: String,
    ): AddSourceResult {
        val canonical = File(path).canonicalPath
        val config = load()
        val existing = config.sources.find { it.name == name }
        val updated =
            if (existing == null) {
                config.copy(sources = config.sources + KitSourceEntry(name, canonical))
            } else {
                config.copy(sources = config.sources.map { if (it.name == name) KitSourceEntry(name, canonical) else it })
            }
        save(updated)
        return if (existing == null) AddSourceResult.ADDED else AddSourceResult.UPDATED
    }

    /**
     * Removes the source with [name]. Returns true if removed, false if not found.
     */
    fun remove(name: String): Boolean {
        val config = load()
        if (config.sources.none { it.name == name }) return false
        save(config.copy(sources = config.sources.filter { it.name != name }))
        return true
    }
}
