package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver.TemplateSource
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.TemplateVariables
import org.koin.core.component.inject
import java.io.File

abstract class BaseInstallCommand : PicoBaseCommand() {
    protected val templateService: TemplateService by inject()
    protected val resolver: InstallTemplateResolver by inject()

    internal fun renderAndWrite(
        source: TemplateSource,
        workloadName: String,
        storageSize: String,
        extraVars: Map<String, String> = emptyMap(),
    ) {
        val vars = TemplateVariables.from(state = clusterState, workloadName = workloadName, storageSize = storageSize)
        val outputDir = File(context.workingDirectory, workloadName)
        outputDir.mkdirs()

        val allUnresolved = mutableListOf<String>()

        for (entry in resolver.listTemplateFiles(source)) {
            val rendered =
                templateService.renderWorkloadTemplate(
                    templateContent = entry.content,
                    vars = vars,
                    extraVars = extraVars,
                ) { unresolved -> allUnresolved.addAll(unresolved) }

            val outputName = entry.name.removeSuffix(".template")
            val outputFile = File(outputDir, outputName)
            outputFile.parentFile.mkdirs()
            outputFile.writeText(rendered)
            val isInBinDir = outputName.startsWith("bin/") || outputName.startsWith("bin${File.separator}")
            if (isInBinDir) outputFile.setExecutable(true)
            eventBus.emit(Event.Install.ArtifactWritten(workload = workloadName, filePath = outputFile.path))
        }

        val installYamlContent = resolver.readInstallYamlContent(source)
        if (installYamlContent != null) {
            File(outputDir, "install.yaml").writeText(installYamlContent)
        }

        if (allUnresolved.isNotEmpty()) {
            eventBus.emit(
                Event.Install.UnresolvedVariables(workload = workloadName, variables = allUnresolved.distinct()),
            )
        }

        eventBus.emit(Event.Install.ScaffoldComplete(workload = workloadName, outputDir = outputDir.path))
    }
}
