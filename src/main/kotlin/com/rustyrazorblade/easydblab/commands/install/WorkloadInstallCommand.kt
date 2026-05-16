package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.WorkloadInstallConfig
import java.io.File

/**
 * Dynamically-created install subcommand backed by an install.yaml config file.
 *
 * Instances are built by [WorkloadInstallCommandFactory] and registered as PicoCLI subcommands
 * of the `install` parent command at startup. Options are wired via PicoCLI's programmatic
 * [OptionSpec][picocli.CommandLine.Model.OptionSpec] API; values land in [argValues] via ISetter
 * callbacks before [execute] is called.
 */
class WorkloadInstallCommand(
    private val config: WorkloadInstallConfig,
    private val source: InstallTemplateResolver.TemplateSource,
) : BaseInstallCommand() {
    internal val argValues: MutableMap<String, String> = mutableMapOf()
    internal var force: Boolean = false

    /**
     * Resolved defaults (variable → value+suffix) set by [WorkloadInstallCommandFactory].
     * Applied to [argValues] for options the user did not explicitly provide, since PicoCLI
     * does not invoke the ISetter for default values in the programmatic API.
     */
    internal val resolvedDefaults: MutableMap<String, String> = mutableMapOf()

    override fun execute() {
        for ((variable, default) in resolvedDefaults) {
            argValues.putIfAbsent(variable, default)
        }

        if (config.collisionCheck && !force) {
            val outputDir = File(context.workingDirectory, config.name)
            if (outputDir.isDirectory && outputDir.listFiles().orEmpty().isNotEmpty()) {
                eventBus.emit(Event.Install.CollisionDetected(workload = config.name))
                return
            }
        }

        val storageSize = argValues.remove("STORAGE_SIZE") ?: "0Gi"
        renderAndWrite(
            source = source,
            workloadName = config.name,
            storageSize = storageSize,
            extraVars = argValues.toMap(),
        )
    }
}
