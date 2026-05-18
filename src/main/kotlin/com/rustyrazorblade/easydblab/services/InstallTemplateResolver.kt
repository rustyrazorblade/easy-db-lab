package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Context
import io.github.classgraph.ClassGraph
import java.io.File
import java.nio.file.Path

/**
 * Resolves workload install templates from three sources in priority order:
 *
 * 1. Profile directory: `~/.easy-db-lab/profiles/<profile>/install/<name>/`
 * 2. Built-in classpath: `install/<name>/` in resources
 * 3. Ad-hoc: any local path supplied via `--from` (not listed by `--list`)
 *
 * Profile templates override built-in templates of the same name.
 */
class InstallTemplateResolver(
    private val context: Context,
) {
    sealed interface TemplateSource {
        /** Template found in the user's profile directory. */
        data class ProfileDirectory(
            val dir: File,
        ) : TemplateSource

        /** Template bundled with the application. */
        data class Builtin(
            val name: String,
        ) : TemplateSource

        /** Ad-hoc template supplied via --from flag (not discoverable by --list). */
        data class AdHoc(
            val dir: File,
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
        get() = File(context.profileDir, "install")

    private val builtinNames: List<String> by lazy { scanBuiltinTemplateNames() }

    private val builtinResourceBase = "com/rustyrazorblade/easydblab/install"

    private fun scanBuiltinTemplateNames(): List<String> =
        ClassGraph()
            .acceptPaths(builtinResourceBase)
            .scan()
            .use { scan ->
                scan.allResources
                    .map { it.path.removePrefix("$builtinResourceBase/") }
                    .filter { it.contains('/') }
                    .map { it.substringBefore('/') }
                    .distinct()
                    .sorted()
            }

    private fun listBuiltinEntries(name: String): List<TemplateEntry> {
        val prefix = "$builtinResourceBase/$name/"
        return ClassGraph()
            .acceptPaths("$builtinResourceBase/$name")
            .scan()
            .use { scan ->
                scan.allResources
                    .filter { it.path.endsWith(".template") }
                    .map { TemplateEntry(it.path.removePrefix(prefix), it.contentAsString) }
            }
    }

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
     * Templates without an `install.yaml` fall back to a minimal config with just the name.
     */
    fun listAvailableTemplateDetails(): List<WorkloadInstallConfig> =
        listAvailableTemplates().map { name ->
            runCatching { loadInstallConfig(resolve(name)) }.getOrNull()
                ?: WorkloadInstallConfig(name = name)
        }

    /**
     * Resolves a named template to its source, checking profile dir before built-ins.
     *
     * @throws IllegalArgumentException if no template with [name] can be found
     */
    fun resolve(name: String): TemplateSource {
        val profileTemplate = File(profileInstallDir, name)
        if (profileTemplate.isDirectory) {
            return TemplateSource.ProfileDirectory(profileTemplate)
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
        return TemplateSource.AdHoc(dir)
    }

    /**
     * Reads and parses `install.yaml` from [source], returning null if the file is absent.
     *
     * A missing `install.yaml` is not an error — templates without one simply have no
     * declared args and cannot generate dynamic subcommands.
     */
    fun readInstallYamlContent(source: TemplateSource): String? =
        when (source) {
            is TemplateSource.ProfileDirectory -> {
                val file = File(source.dir, "install.yaml")
                if (file.isFile) file.readText() else null
            }
            is TemplateSource.AdHoc -> {
                val file = File(source.dir, "install.yaml")
                if (file.isFile) file.readText() else null
            }
            is TemplateSource.Builtin -> {
                val resourcePath = "$builtinResourceBase/${source.name}/install.yaml"
                InstallTemplateResolver::class.java.classLoader
                    .getResourceAsStream(resourcePath)
                    ?.bufferedReader()
                    ?.readText()
            }
        }

    fun loadInstallConfig(source: TemplateSource): WorkloadInstallConfig? =
        when (source) {
            is TemplateSource.ProfileDirectory -> {
                val file = File(source.dir, "install.yaml")
                if (file.isFile) installConfigYaml.decodeFromString(WorkloadInstallConfig.serializer(), file.readText()) else null
            }
            is TemplateSource.AdHoc -> {
                val file = File(source.dir, "install.yaml")
                if (file.isFile) installConfigYaml.decodeFromString(WorkloadInstallConfig.serializer(), file.readText()) else null
            }
            is TemplateSource.Builtin -> {
                val resourcePath = "$builtinResourceBase/${source.name}/install.yaml"
                InstallTemplateResolver::class.java.classLoader
                    .getResourceAsStream(resourcePath)
                    ?.bufferedReader()
                    ?.readText()
                    ?.let { installConfigYaml.decodeFromString(WorkloadInstallConfig.serializer(), it) }
            }
        }

    /**
     * Lists all `.template` files in a template source as [TemplateEntry] objects.
     *
     * [TemplateEntry.name] is the relative path from the template root, preserving
     * subdirectory structure (e.g. `bin/start.sh.template`).
     */
    fun listTemplateFiles(source: TemplateSource): List<TemplateEntry> =
        when (source) {
            is TemplateSource.ProfileDirectory ->
                source.dir
                    .walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".template") }
                    .map { TemplateEntry(it.relativeTo(source.dir).path, it.readText()) }
                    .toList()
            is TemplateSource.AdHoc ->
                source.dir
                    .walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".template") }
                    .map { TemplateEntry(it.relativeTo(source.dir).path, it.readText()) }
                    .toList()
            is TemplateSource.Builtin -> listBuiltinEntries(source.name)
        }
}
