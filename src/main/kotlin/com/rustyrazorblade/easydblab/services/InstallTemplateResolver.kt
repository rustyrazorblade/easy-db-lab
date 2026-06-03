package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.events.Event
import io.github.classgraph.ClassGraph
import java.io.File
import java.nio.file.Path

/**
 * Resolves kit install templates from three sources in priority order:
 *
 * 1. Profile directory: `~/.easy-db-lab/profiles/<profile>/kits/<name>/`
 * 2. Built-in classpath: `kits/<name>/` in resources
 * 3. Ad-hoc: any local path supplied via `--from` (not listed by `--list`)
 *
 * Profile templates override built-in templates of the same name.
 */
class InstallTemplateResolver(
    private val context: Context,
) {
    sealed interface TemplateSource {
        /** Template loaded from a local directory (profile override or --from path). */
        data class Directory(
            val dir: File,
        ) : TemplateSource

        /** Template bundled with the application. */
        data class Builtin(
            val name: String,
        ) : TemplateSource
    }

    /**
     * A resolved template file ready to render.
     * [name] is the relative path from the template root (e.g. `bin/start.sh.template`).
     */
    data class TemplateEntry(
        val name: String,
        val content: String,
    )

    private val profileInstallDir: File
        get() = File(context.profileDir, "kits")

    private val builtinResourceBase = "com/rustyrazorblade/easydblab/kits"

    private val builtinEntries: Map<String, List<TemplateEntry>> by lazy { scanBuiltinEntries() }
    private val builtinNames: List<String> get() = builtinEntries.keys.sorted()

    private fun scanBuiltinEntries(): Map<String, List<TemplateEntry>> =
        ClassGraph()
            .acceptPaths(builtinResourceBase)
            .scan()
            .use { scan ->
                scan.allResources
                    .filter { it.path.removePrefix("$builtinResourceBase/").contains('/') }
                    .groupBy { it.path.removePrefix("$builtinResourceBase/").substringBefore('/') }
                    .mapValues { (name, resources) ->
                        val prefix = "$builtinResourceBase/$name/"
                        resources
                            .filter { !it.path.endsWith(Constants.Kit.CONFIG_FILE) }
                            .map { TemplateEntry(it.path.removePrefix(prefix), it.contentAsString) }
                    }
            }

    private fun listBuiltinEntries(name: String): List<TemplateEntry> = builtinEntries[name] ?: emptyList()

    /**
     * Lists all discoverable template names: profile-directory names plus built-in names.
     * Profile names override built-ins of the same name in the returned set.
     * Ad-hoc (--from) templates are not included.
     */
    fun listAvailableTemplates(): List<String> {
        val profileNames =
            profileInstallDir
                .takeIf { it.isDirectory }
                ?.listFiles { f -> f.isDirectory }
                ?.map { it.name }
                ?: emptyList()
        return (profileNames + builtinNames).distinct().sorted()
    }

    /**
     * Returns install config metadata for all discoverable templates, sorted by name.
     * Templates without a `config.yaml` fall back to a minimal config with just the name.
     */
    fun listAvailableTemplateDetails(): List<Event.Install.TemplateDetail> =
        listAvailableTemplates().map { name ->
            val config =
                runCatching { loadInstallConfig(resolve(name)) }.getOrNull()
                    ?: KitConfig(name = name)
            Event.Install.TemplateDetail(
                name = config.name,
                version = config.version,
                description = config.description,
            )
        }

    /**
     * Resolves a named template to its source, checking profile dir before built-ins.
     *
     * @throws IllegalArgumentException if no template with [name] can be found
     */
    fun resolve(name: String): TemplateSource {
        val profileTemplate = File(profileInstallDir, name)
        if (profileTemplate.isDirectory) {
            return TemplateSource.Directory(profileTemplate)
        }
        if (name in builtinNames) {
            return TemplateSource.Builtin(name)
        }
        throw IllegalArgumentException("No install template found for '$name'. Use --list to see available templates.")
    }

    /**
     * Wraps an ad-hoc path (from --from flag) as a [TemplateSource].
     *
     * @throws IllegalArgumentException if [path] is not an existing directory
     */
    fun resolveAdHoc(path: Path): TemplateSource {
        val dir = path.toFile()
        require(dir.isDirectory) { "Template path does not exist or is not a directory: $path" }
        return TemplateSource.Directory(dir)
    }

    /**
     * Reads and parses `config.yaml` from [source], returning null if the file is absent.
     *
     * A missing `config.yaml` is not an error — templates without one simply have no
     * declared args and cannot generate dynamic subcommands.
     */
    fun readInstallYamlContent(source: TemplateSource): String? =
        when (source) {
            is TemplateSource.Directory -> {
                val file = File(source.dir, Constants.Kit.CONFIG_FILE)
                if (file.isFile) file.readText() else null
            }
            is TemplateSource.Builtin -> {
                val resourcePath = "$builtinResourceBase/${source.name}/${Constants.Kit.CONFIG_FILE}"
                InstallTemplateResolver::class.java.classLoader
                    .getResourceAsStream(resourcePath)
                    ?.bufferedReader()
                    ?.readText()
            }
        }

    fun loadInstallConfig(source: TemplateSource): KitConfig? =
        readInstallYamlContent(source)
            ?.let { installConfigYaml.decodeFromString(KitConfig.serializer(), it) }

    /**
     * Lists all `.template` files in a template source as [TemplateEntry] objects.
     *
     * [TemplateEntry.name] is the relative path from the template root, preserving
     * subdirectory structure (e.g. `bin/start.sh.template`).
     */
    fun listTemplateFiles(source: TemplateSource): List<TemplateEntry> =
        when (source) {
            is TemplateSource.Directory ->
                source.dir
                    .walkTopDown()
                    .filter { it.isFile && it.name != Constants.Kit.CONFIG_FILE }
                    .map { TemplateEntry(it.relativeTo(source.dir).path, it.readText()) }
                    .toList()
            is TemplateSource.Builtin -> listBuiltinEntries(source.name)
        }
}
