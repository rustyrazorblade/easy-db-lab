package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver.TemplateSource
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.TemplateVariables
import org.koin.core.component.inject
import java.io.File
import java.util.UUID

abstract class BaseInstallCommand : PicoBaseCommand() {
    protected val templateService: TemplateService by inject()
    protected val resolver: InstallTemplateResolver by inject()

    internal fun renderAndWrite(
        source: TemplateSource,
        kitName: String,
        storageSize: String,
        extraVars: Map<String, String> = emptyMap(),
    ) {
        val vars = TemplateVariables.from(state = clusterState, kitName = kitName, storageSize = storageSize)
        val outputDir = File(context.workingDirectory, kitName)
        val tempDir = File(context.workingDirectory, ".tmp-$kitName-${UUID.randomUUID()}")
        tempDir.mkdirs()

        val allUnresolved = mutableListOf<String>()
        val writtenNames = mutableListOf<String>()

        try {
            for (entry in resolver.listTemplateFiles(source)) {
                val isTemplate = entry.name.endsWith(".template")
                val outputName = if (isTemplate) entry.name.removeSuffix(".template") else entry.name
                val content =
                    if (isTemplate) {
                        templateService.renderKitTemplate(
                            templateContent = entry.content,
                            vars = vars,
                            extraVars = extraVars,
                        ) { unresolved -> allUnresolved.addAll(unresolved) }
                    } else {
                        entry.content
                    }

                val tempFile = File(tempDir, outputName)
                tempFile.parentFile.mkdirs()
                tempFile.writeText(content)
                val isInBinDir = outputName.startsWith("bin/") || outputName.startsWith("bin${File.separator}")
                if (isInBinDir) tempFile.setExecutable(true)
                writtenNames.add(outputName)
            }

            val installYamlContent = resolver.readInstallYamlContent(source)
            if (installYamlContent != null) {
                val renderedYaml = patchKitYaml(installYamlContent, extraVars)
                File(tempDir, Constants.Kit.CONFIG_FILE).writeText(renderedYaml)
            }

            // Persist all resolved kit arg values so later phases (start, stop, etc.) can
            // use the values the user actually passed at install time rather than the kit
            // defaults. Critical for platform-pvs: without this, STORAGE_SIZE reverts to
            // the kit default (e.g. 10Ti) instead of the installed value (e.g. 100Gi),
            // causing PV capacity to mismatch the PVC request in the rendered manifest.
            val resolvedArgs = extraVars + ("STORAGE_SIZE" to storageSize)
            File(tempDir, Constants.Kit.RESOLVED_ARGS_FILE).writeText(
                resolvedArgs.entries.joinToString("\n") { (k, v) -> "$k=$v" } + "\n",
            )

            // Atomic swap: delete any existing kit dir then rename the fully-rendered
            // temp dir into place. outputDir is never in a partial state.
            outputDir.deleteRecursively()
            check(tempDir.renameTo(outputDir)) {
                "Failed to rename scaffold to ${outputDir.absolutePath}"
            }
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            throw e
        }

        for (name in writtenNames) {
            println("  Written: ${File(outputDir, name).path}")
        }

        if (allUnresolved.isNotEmpty()) {
            eventBus.emit(
                Event.Install.UnresolvedVariables(kit = kitName, variables = allUnresolved.distinct()),
            )
        }

        eventBus.emit(Event.Install.ScaffoldComplete(kit = kitName, outputDir = outputDir.path))
    }

    /**
     * Rewrites YAML `port:` lines in [content] whose current value matches a kit.yaml
     * default port, substituting the override value from [extraVars].
     *
     * Kit templates declare static default port values in kit.yaml (e.g. `port: 30432`).
     * When an extension overrides the ports via POSTGRES_PORT / METRICS_PORT, the written
     * workspace kit.yaml must reflect those values so that endpoint resolution and the
     * `sql` subcommand connect to the correct NodePort. The source kit.yaml is valid YAML
     * (it uses integer literals), so this method replaces default values with overridden
     * ones line-by-line, targeting only `port: <integer>` scalar lines.
     *
     * A substitution is applied when the current port value in the YAML exactly matches
     * the default stored under the same variable name (POSTGRES_PORT or METRICS_PORT) in
     * [extraVars] is not needed — instead we use a direct mapping from variable name to
     * the parsed override integer port.
     */
    internal fun patchKitYaml(
        content: String,
        extraVars: Map<String, String>,
    ): String {
        val postgresPort = extraVars["POSTGRES_PORT"]?.toIntOrNull()
        val metricsPort = extraVars["METRICS_PORT"]?.toIntOrNull()
        if (postgresPort == null && metricsPort == null) return content

        return content
            .lines()
            .map { line ->
                val trimmed = line.trimStart()
                if (!trimmed.startsWith("port:")) return@map line

                val currentPort = trimmed.removePrefix("port:").trim().toIntOrNull() ?: return@map line
                val indent = " ".repeat(line.length - trimmed.length)

                // Map from known default ports to their override values.
                // POSTGRES_PORT defaults: 30432. METRICS_PORT defaults: 30987.
                // When the kit.yaml line holds a default that is being overridden, replace it.
                val newPort =
                    when {
                        postgresPort != null && isDefaultPostgresPort(currentPort) -> postgresPort
                        metricsPort != null && isDefaultMetricsPort(currentPort) -> metricsPort
                        else -> return@map line
                    }

                "${indent}port: $newPort"
            }.joinToString("\n")
    }

    /**
     * Returns true if [port] is a well-known postgres NodePort default that may be
     * overridden by the POSTGRES_PORT variable. The set of well-known defaults matches
     * the port values used in the extensions.yaml registry.
     */
    private fun isDefaultPostgresPort(port: Int): Boolean = port in setOf(30432, 30433, 30434)

    /**
     * Returns true if [port] is a well-known metrics NodePort default that may be
     * overridden by the METRICS_PORT variable.
     */
    private fun isDefaultMetricsPort(port: Int): Boolean = port in setOf(30987, 30988, 30989)
}
