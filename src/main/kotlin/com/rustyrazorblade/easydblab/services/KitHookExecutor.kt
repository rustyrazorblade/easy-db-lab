package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import java.io.File

interface KitHookExecutor {
    fun firePostKitStart(triggeringKit: String)

    fun firePostKitStop(triggeringKit: String)
}

class DefaultKitHookExecutor(
    private val context: Context,
    private val clusterStateManager: ClusterStateManager,
    private val eventBus: EventBus,
    private val maxAttempts: Int = MAX_HOOK_ATTEMPTS,
    private val backoffBaseMs: Long = HOOK_BACKOFF_BASE_MS,
) : KitHookExecutor {
    override fun firePostKitStart(triggeringKit: String) {
        val clusterState = clusterStateManager.load()
        fireHooks(triggeringKit, clusterState) { hooks -> hooks.postWorkloadStart }
    }

    override fun firePostKitStop(triggeringKit: String) {
        val clusterState = clusterStateManager.load()
        fireHooks(triggeringKit, clusterState) { hooks -> hooks.postWorkloadStop }
    }

    private fun fireHooks(
        triggeringKit: String,
        clusterState: ClusterState,
        hookSelector: (KitHooks) -> KitHook?,
    ) {
        val runningKits = clusterState.runningKits
        context.workingDirectory
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .filter { it.name in runningKits }
            .filter { it.name != triggeringKit }
            .sortedBy { it.name }
            .forEach { kitDir ->
                val declaringKit = kitDir.name

                val configYaml = File(kitDir, Constants.Kit.CONFIG_FILE)
                if (!configYaml.isFile) return@forEach

                val config =
                    runCatching {
                        installConfigYaml.decodeFromString(KitConfig.serializer(), configYaml.readText())
                    }.getOrElse { e ->
                        log.warn(e) { "Failed to parse ${Constants.Kit.CONFIG_FILE} for $declaringKit — skipping hooks" }
                        return@forEach
                    }

                val hooks = config.hooks ?: return@forEach
                val hook = hookSelector(hooks) ?: return@forEach

                if (hook.workloads.isNotEmpty() && triggeringKit !in hook.workloads) return@forEach

                val envVars =
                    TemplateVariables
                        .from(state = clusterState, kitName = declaringKit, storageSize = "")
                        .toMap()

                executeHookWithRetry(declaringKit, hook, kitDir, envVars)
            }
    }

    private val retryConfig: RetryConfig by lazy {
        RetryConfig
            .custom<Unit>()
            .maxAttempts(maxAttempts)
            .intervalFunction { attempt -> backoffBaseMs * (1L shl (attempt - 1)) }
            .retryOnException { it !is InterruptedException }
            .build()
    }

    private fun executeHookWithRetry(
        declaringKit: String,
        hook: KitHook,
        kitDir: File,
        envVars: Map<String, String>,
    ) {
        val retry = Retry.of("hook-$declaringKit-${hook.script}", retryConfig)
        val scriptFile = File(kitDir, hook.script).canonicalFile
        require(scriptFile.path.startsWith(kitDir.canonicalPath + File.separator)) {
            "Hook script path '${hook.script}' in kit '$declaringKit' escapes the kit directory"
        }

        runCatching {
            Retry
                .decorateSupplier(retry) {
                    val process =
                        ProcessBuilder(scriptFile.absolutePath)
                            .directory(context.workingDirectory)
                            .inheritIO()
                            .also { pb -> pb.environment().putAll(envVars) }
                            .start()
                    try {
                        val exitCode = process.waitFor()
                        if (exitCode != 0) throw RuntimeException("hook script exited with code $exitCode")
                    } finally {
                        process.destroyForcibly()
                    }
                }.get()
        }.onFailure { e ->
            val cause = e.cause ?: e
            eventBus.emit(
                Event.Kit.HookFailed(
                    kit = declaringKit,
                    hook = hook.script,
                    reason = cause.message ?: "unknown error",
                ),
            )
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private const val MAX_HOOK_ATTEMPTS = 3
        private const val HOOK_BACKOFF_BASE_MS = 1000L
    }
}
