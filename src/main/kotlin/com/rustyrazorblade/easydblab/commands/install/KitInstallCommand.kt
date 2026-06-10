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
import com.rustyrazorblade.easydblab.services.installConfigYaml
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

        validateKitRefCapability()

        val instanceName = resolveInstanceName()

        if (config.collisionCheck && !force) {
            val outputDir = File(context.workingDirectory, instanceName)
            if (outputDir.isDirectory && outputDir.listFiles().orEmpty().isNotEmpty()) {
                eventBus.emit(Event.Install.CollisionDetected(kit = instanceName))
                return
            }
        }

        val storageSize = argValues.remove("STORAGE_SIZE").orEmpty()
        renderAndWrite(
            source = source,
            kitName = instanceName,
            storageSize = storageSize,
            extraVars = argValues.toMap(),
        )

        if (config.install.isNotEmpty()) {
            val controlHost =
                clusterState.getControlHost()
                    ?: error("No control node found in cluster state")
            val kitDir = File(context.workingDirectory, instanceName)
            val variables =
                TemplateVariables
                    .from(state = clusterState, kitName = instanceName, storageSize = storageSize)
                    .toMap() + argValues

            runCatching {
                workloadStepExecutor
                    .execute(
                        steps = config.install,
                        phase = Constants.Kit.PHASE_INSTALL,
                        context =
                            StepExecutionContext(
                                kitName = instanceName,
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

    /**
     * Validates that the target kit declared by a kit-ref arg exposes the required capability.
     * Fails fast with a clear error rather than letting the bench kit fail at runtime.
     */
    private fun validateKitRefCapability() {
        val kitRefArg = config.kitRefArg ?: return
        val requiredCapability = kitRefArg.capability.takeIf { it.isNotBlank() } ?: return
        val targetName = argValues[kitRefArg.variable]?.takeIf { it.isNotBlank() } ?: return

        val targetKitDir = File(context.workingDirectory, targetName)
        val configFile = File(targetKitDir, Constants.Kit.CONFIG_FILE)
        check(configFile.isFile) {
            "Target kit '$targetName' is not installed (expected $configFile)"
        }

        val targetConfig = installConfigYaml.decodeFromString(KitConfig.serializer(), configFile.readText())
        val hasCapability = targetConfig.capabilities.any { it.type == requiredCapability }
        check(hasCapability) {
            "Target kit '$targetName' does not have the '$requiredCapability' capability required by ${config.name}"
        }
    }

    /**
     * Computes the directory name for the installed kit. For kits with a kit-ref arg,
     * the directory is named `<kit>-<target>` so the same bench kit can be installed
     * against multiple databases simultaneously (e.g. sysbench-clickhouse, sysbench-mysql).
     * For kits without a kit-ref arg, the directory is the kit's own name.
     */
    private fun resolveInstanceName(): String =
        config.kitRefArg
            ?.let { argValues[it.variable]?.takeIf { v -> v.isNotBlank() } }
            ?.let { "${config.name}-$it" }
            ?: config.name
}
