package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitType
import com.rustyrazorblade.easydblab.services.StepExecutionContext
import com.rustyrazorblade.easydblab.services.TemplateVariables
import com.rustyrazorblade.easydblab.services.WorkloadStepExecutor
import org.koin.core.component.inject
import java.io.File

/**
 * Dynamically-created install subcommand backed by a kit.yaml kit descriptor.
 *
 * Instances are built by [KitInstallCommandFactory] and registered as PicoCLI subcommands
 * of the `install` parent command at startup. Options are wired via PicoCLI's programmatic
 * [OptionSpec][picocli.CommandLine.Model.OptionSpec] API; values land in [argValues] via ISetter
 * callbacks before [execute] is called.
 */
class KitInstallCommand(
    private val config: KitConfig,
    private val source: InstallTemplateResolver.TemplateSource,
) : BaseInstallCommand() {
    private val workloadStepExecutor: WorkloadStepExecutor by inject()

    internal val argValues: MutableMap<String, String> = mutableMapOf()
    internal var force: Boolean = false

    /**
     * Resolved defaults (variable → value+suffix) set by [KitInstallCommandFactory].
     * Applied to [argValues] for options the user did not explicitly provide, since PicoCLI
     * does not invoke the ISetter for default values in the programmatic API.
     */
    internal val resolvedDefaults: MutableMap<String, String> = mutableMapOf()

    override fun execute() {
        // Fail fast if the cluster lacks the required node pool
        config.type?.let { kitType ->
            val serverType =
                when (kitType) {
                    KitType.DB -> ServerType.Cassandra
                    KitType.APP -> ServerType.Stress
                }
            if (clusterState.getHosts(serverType).isEmpty()) {
                eventBus.emit(Event.Kit.RequirementNotMet(kit = config.name, nodeType = kitType.name.lowercase()))
                return
            }
        }

        for ((variable, default) in resolvedDefaults) {
            argValues.putIfAbsent(variable, default)
        }

        if (config.collisionCheck && !force) {
            val outputDir = File(context.workingDirectory, config.name)
            if (outputDir.isDirectory && outputDir.listFiles().orEmpty().isNotEmpty()) {
                eventBus.emit(Event.Install.CollisionDetected(kit = config.name))
                return
            }
        }

        val storageSize = argValues.remove("STORAGE_SIZE").orEmpty()
        renderAndWrite(
            source = source,
            kitName = config.name,
            storageSize = storageSize,
            extraVars = argValues.toMap(),
        )

        if (config.install.isNotEmpty()) {
            val controlHost =
                clusterState.getControlHost()
                    ?: error("No control node found in cluster state")
            val kitDir = File(context.workingDirectory, config.name)
            val variables =
                TemplateVariables
                    .from(state = clusterState, kitName = config.name, storageSize = storageSize)
                    .toMap() + argValues

            runCatching {
                workloadStepExecutor
                    .execute(
                        steps = config.install,
                        phase = Constants.Kit.PHASE_INSTALL,
                        context =
                            StepExecutionContext(
                                kitName = config.name,
                                controlHost = controlHost,
                                clusterState = clusterState,
                                variables = variables,
                                kitDir = kitDir,
                            ),
                    ).getOrThrow()
            }.onFailure { e ->
                kitDir.deleteRecursively()
                throw e
            }
        }
    }
}
