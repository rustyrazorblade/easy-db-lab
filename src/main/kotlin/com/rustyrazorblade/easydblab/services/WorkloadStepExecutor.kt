package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.messageOrString
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import com.rustyrazorblade.easydblab.configuration.KitStorage as EnvironmentKitStorage

private val log = KotlinLogging.logger {}

internal fun interpolateStepVars(
    text: String,
    variables: Map<String, String>,
): String =
    text.replace(Constants.Kit.SHELL_VAR_PATTERN) { match ->
        val key = match.groupValues[1]
        val resolved = variables[key]
        if (resolved == null) {
            log.warn { "Unresolved step variable: \${$key}" }
        }
        resolved ?: match.value
    }

/**
 * A kit `shell` step exited non-zero. Carries the [exitCode] and the last lines of the script's
 * output, which [WorkloadStepExecutor] reports as [Event.Kit.ShellStepFailed].
 */
class ShellStepFailedException(
    val exitCode: Int,
    val outputTail: List<String>,
) : RuntimeException("shell step exited with code $exitCode")

/**
 * Bundles the per-execution context that every step needs, eliminating the
 * 7-parameter signatures on [WorkloadStepExecutor.execute] and [executeStep].
 *
 * @property kitDir the kit instance's directory, where shell steps run and relative files resolve
 * @property workspaceDir the cluster workspace, whose `bin/` wrappers and kubeconfig shell steps get
 */
data class StepExecutionContext(
    val kitName: String,
    val clusterState: ClusterState,
    val variables: Map<String, String>,
    val kitDir: File,
    val workspaceDir: File,
) {
    /** The cluster namespace, where a step that names no namespace acts. */
    val namespace: String get() = scope.namespace.value

    /** The cluster's install scope: its namespace and the ownership labels of what it creates. */
    val scope: InstallScope get() = InstallScope.of(clusterState, ClusterContract.of(clusterState))
}

/**
 * Runs a kit's typed steps, in `install:` and in its lifecycle phases.
 *
 * Most steps act on the cluster through [ClusterTooling] or fabric8. A `shell` step runs on the
 * operator's machine through [KitProcessEnvironment], so it gets the workspace tool wrappers and
 * the absolute workspace kubeconfig.
 */
class WorkloadStepExecutor(
    private val k8sService: K8sService,
    private val helmService: HelmService,
    private val kubectlService: KubectlService,
    private val eventBus: EventBus,
    private val kitProcessEnvironment: KitProcessEnvironment,
    private val kitStorageProvisioner: KitStorageProvisioner,
) {
    fun execute(
        steps: List<InstallStep>,
        phase: String,
        context: StepExecutionContext,
    ): Result<Unit> =
        runCatching {
            steps.forEachIndexed { index, step ->
                val stepType = step::class.simpleName ?: "Unknown"
                eventBus.emit(
                    Event.Kit.StepStarted(
                        kit = context.kitName,
                        phase = phase,
                        stepType = stepType,
                        stepIndex = index,
                    ),
                )
                executeStep(step, context).onFailure { error ->
                    eventBus.emit(
                        when (error) {
                            is ShellStepFailedException ->
                                Event.Kit.ShellStepFailed(
                                    kit = context.kitName,
                                    phase = phase,
                                    stepIndex = index,
                                    exitCode = error.exitCode,
                                    outputTail = error.outputTail,
                                )
                            else ->
                                Event.Kit.StepFailed(
                                    kit = context.kitName,
                                    phase = phase,
                                    stepType = stepType,
                                    stepIndex = index,
                                    error = error.messageOrString(),
                                )
                        },
                    )
                    throw error
                }
            }
        }

    private fun executeStep(
        step: InstallStep,
        ctx: StepExecutionContext,
    ): Result<Unit> =
        runCatching {
            fun interp(s: String) = interpolateStepVars(s, ctx.variables)

            // A step that names no namespace acts in the cluster namespace.
            fun ns(stepNamespace: String?) = stepNamespace?.let(::interp) ?: ctx.namespace

            when (step) {
                is InstallStep.HelmRepo -> {
                    helmService.repoAdd(name = interp(step.name), url = interp(step.url))
                }

                is InstallStep.Helm -> {
                    val valuesFile =
                        if (step.valuesFile.isNotEmpty()) {
                            File(ctx.kitDir, interp(step.valuesFile))
                        } else {
                            null
                        }
                    helmService.upgradeInstall(
                        release = interp(step.release),
                        chart = interp(step.chart),
                        namespace = ns(step.namespace),
                        version = step.version?.let { interp(it) },
                        values = step.values.mapKeys { (k, _) -> interp(k) }.mapValues { (_, v) -> interp(v) },
                        valuesFile = valuesFile,
                        createNamespace = true,
                    )
                }

                is InstallStep.HelmUninstall -> {
                    val release = interp(step.release)
                    val usedBy =
                        if (step.keepWhileAny.isBlank()) {
                            emptyList()
                        } else {
                            kubectlService.listInAllNamespaces(resource = interp(step.keepWhileAny))
                        }
                    if (usedBy.isEmpty()) {
                        helmService.uninstall(release = release, namespace = ns(step.namespace))
                    } else {
                        eventBus.emit(Event.Kit.HelmReleaseKept(kit = ctx.kitName, release = release, usedBy = usedBy))
                    }
                }

                is InstallStep.Namespace -> {
                    // An operator's own namespace carries the cluster's labels too (design D7).
                    kubectlService.createNamespace(name = interp(step.name), labels = ctx.scope.labels)
                }

                is InstallStep.Manifest -> {
                    val manifestFile = File(ctx.kitDir, interp(step.template))
                    require(manifestFile.isFile) {
                        "Manifest file not found: ${manifestFile.absolutePath}. Run 'install ${ctx.kitName}' first."
                    }
                    val content = if (step.interpolate) interp(manifestFile.readText()) else manifestFile.readText()
                    kubectlService.applyContent(yamlContent = content)
                }

                is InstallStep.ManifestUrl -> {
                    kubectlService.applyUrl(url = interp(step.url))
                }

                is InstallStep.Kustomize -> {
                    kubectlService.applyKustomize(url = interp(step.url))
                }

                is InstallStep.Wait -> {
                    kubectlService.wait(
                        kind = interp(step.kind),
                        name = interp(step.name),
                        condition = interp(step.condition),
                        namespace = ns(step.namespace),
                        timeout = interp(step.timeout),
                    )
                }

                is InstallStep.Delete -> {
                    val namespace = ns(step.namespace)
                    if (step.bySelector) {
                        kubectlService.deleteBySelector(
                            kinds = step.kinds.map { interp(it) },
                            selector = interp(step.selector),
                            namespace = namespace,
                        )
                    } else {
                        kubectlService.delete(
                            kind = interp(step.kind),
                            name = interp(step.name),
                            namespace = namespace,
                            ignoreNotFound = step.ignoreNotFound,
                        )
                    }
                }

                is InstallStep.KitStorage -> {
                    if (step.ifSet.isNotBlank() && ctx.variables[step.ifSet].isNullOrBlank()) {
                        log.info { "Skipping kit-storage for ${ctx.kitName}: ${step.ifSet} is not set" }
                    } else {
                        val storageSize = interp(step.storageSize).ifBlank { ctx.variables["STORAGE_SIZE"].orEmpty() }
                        require(storageSize.isNotBlank()) {
                            "kit-storage step needs a storage-size or the STORAGE_SIZE variable"
                        }
                        kitStorageProvisioner.provision(
                            volumes =
                                KitVolumes(
                                    kit = ctx.kitName,
                                    nodeType = interp(step.nodeType),
                                    volumeClaimTemplateName = interp(step.volumeClaimTemplateName),
                                    size = storageSize,
                                    count = step.count,
                                    rebindReleased = step.rebindReleased,
                                ),
                            storage = EnvironmentKitStorage.from(ctx.clusterState),
                            state = ctx.clusterState,
                        )
                    }
                }

                is InstallStep.KitStorageDelete ->
                    kitStorageProvisioner.release(
                        kit = ctx.kitName,
                        nodeType = interp(step.nodeType),
                        storage = EnvironmentKitStorage.from(ctx.clusterState),
                        state = ctx.clusterState,
                    )

                is InstallStep.ConfigMap -> {
                    k8sService
                        .createConfigMap(
                            namespace = ns(step.namespace),
                            name = interp(step.name),
                            data = step.data.mapValues { (_, v) -> interp(v) },
                        ).getOrThrow()
                }

                is InstallStep.Label -> {
                    val nodeType = interp(step.nodeType)
                    val targetHosts =
                        ctx.clusterState.hosts[ServerType.from(nodeType)]
                            ?: error("No $nodeType nodes found for label step")
                    for (host in targetHosts) {
                        k8sService
                            .labelNode(
                                nodeName = host.alias,
                                labels = step.labels.mapKeys { (k, _) -> interp(k) }.mapValues { (_, v) -> interp(v) },
                            ).getOrThrow()
                    }
                }

                is InstallStep.Exec -> {
                    k8sService
                        .execInPod(
                            namespace = ns(step.namespace),
                            podName = interp(step.pod),
                            command = step.command.map { interp(it) },
                        ).getOrThrow()
                }

                is InstallStep.Shell -> runShellStep(step, ctx)
            }
        }

    /**
     * Runs a shell step from the kit directory with the step variables in its environment, the
     * workspace tool wrappers first on `PATH`, and the absolute workspace kubeconfig. Its
     * output (stdout and stderr, merged) is passed through to the console as it runs, and the
     * last [Constants.Kit.SHELL_STEP_OUTPUT_TAIL_LINES] lines are kept on the failure event for
     * structured consumers; the console already showed them, so its rendering does not repeat them.
     *
     * @throws ShellStepFailedException when the script exits non-zero
     * @throws IllegalStateException when the workspace kubeconfig is missing; the script never starts
     */
    private fun runShellStep(
        step: InstallStep.Shell,
        ctx: StepExecutionContext,
    ) {
        val tmpScript = Files.createTempFile("edl-step-", ".sh").toFile()
        try {
            tmpScript.writeText("#!/bin/bash\n${step.script}\n")
            tmpScript.setExecutable(true)
            val builder =
                ProcessBuilder(tmpScript.absolutePath)
                    .directory(ctx.kitDir)
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectErrorStream(true)
            val process = kitProcessEnvironment.applyTo(builder, ctx.workspaceDir, ctx.variables).start()
            val tail = ArrayDeque<String>(Constants.Kit.SHELL_STEP_OUTPUT_TAIL_LINES)
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    println(line)
                    if (tail.size == Constants.Kit.SHELL_STEP_OUTPUT_TAIL_LINES) tail.removeFirst()
                    tail.addLast(line)
                }
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) throw ShellStepFailedException(exitCode, tail.toList())
        } finally {
            tmpScript.delete()
        }
    }
}
