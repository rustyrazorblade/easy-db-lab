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
                File(tempDir, Constants.Kit.CONFIG_FILE).writeText(installYamlContent)
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
}
