package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.ExtensionRegistry
import com.rustyrazorblade.easydblab.services.ExtensionResolver
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

        val extensionVars = buildExtensionVars()
        val storageSize = argValues.remove("STORAGE_SIZE").orEmpty()
        renderAndWrite(
            source = source,
            kitName = instanceName,
            storageSize = storageSize,
            extraVars = extensionVars + argValues.toMap(),
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
     * Computes the directory name for the installed kit.
     *
     * - Kit-ref arg: `<kit>-<target>` (e.g. sysbench-clickhouse) so the same bench kit can
     *   target multiple databases simultaneously.
     * - Extension arg: `<kit>-<extension>` (e.g. postgres-duckdb) so the same kit can be
     *   installed with different extensions simultaneously.
     * - Otherwise: the kit's own name.
     */
    private fun resolveInstanceName(): String {
        config.kitRefArg
            ?.let { argValues[it.variable]?.takeIf { v -> v.isNotBlank() } }
            ?.let { return "${config.name}-$it" }
        config.extensionArg
            ?.let { argValues[it.variable]?.takeIf { v -> v.isNotBlank() } }
            ?.let { return "${config.name}-$it" }
        return config.name
    }

    /**
     * Resolves extension flags into IMAGE, SHARED_PRELOAD_LIBRARIES, and POST_INIT_SQL
     * template variables. Returns an empty map for kits that don't declare an extension arg.
     */
    private fun buildExtensionVars(): Map<String, String> {
        val extArg = config.extensionArg ?: return emptyMap()
        val registry =
            when (source) {
                is InstallTemplateResolver.TemplateSource.Directory ->
                    ExtensionRegistry.fromFile(File(source.dir, "extensions.yaml"))
                is InstallTemplateResolver.TemplateSource.Builtin ->
                    ExtensionRegistry.fromClasspath(source.name)
            }
        val extensionName = argValues[extArg.variable]?.takeIf { it.isNotBlank() }
        val versionVarName = config.args.firstOrNull { it.flag == "--version" }?.variable ?: "POSTGRES_VERSION"
        val postgresVersion = argValues[versionVarName] ?: "17"
        val extConfig =
            ExtensionResolver(registry, postgresVersion).resolve(
                extensions = if (extensionName != null) listOf(extensionName) else emptyList(),
                imageOverride = null,
                additionalPreload = emptyList(),
                additionalCreate = emptyList(),
            )
        val postInitSql =
            if (extConfig.createExtensions.isEmpty()) {
                "[]"
            } else {
                "[${extConfig.createExtensions.joinToString(", ") { "\"CREATE EXTENSION $it\"" }}]"
            }
        return mapOf(
            "IMAGE" to extConfig.image,
            "SHARED_PRELOAD_LIBRARIES" to
                if (extConfig.sharedPreloadLibraries.isEmpty()) {
                    "[]"
                } else {
                    "[${extConfig.sharedPreloadLibraries.joinToString(", ") { "\"$it\"" }}]"
                },
            "POST_INIT_SQL" to postInitSql,
            "PG_MAJOR_VERSION" to postgresVersion.substringBefore("."),
            "POSTGRES_UID" to extConfig.postgresUid.toString(),
            "POSTGRES_GID" to extConfig.postgresGid.toString(),
            "POSTGRES_PORT" to extConfig.postgresPort.toString(),
            "METRICS_PORT" to extConfig.metricsPort.toString(),
        )
    }
}
