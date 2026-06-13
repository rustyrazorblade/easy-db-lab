package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files

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
 * Bundles the per-execution context that every step needs, eliminating the
 * 7-parameter signatures on [WorkloadStepExecutor.execute] and [executeStep].
 */
data class StepExecutionContext(
    val kitName: String,
    val controlHost: ClusterHost,
    val clusterState: ClusterState,
    val variables: Map<String, String>,
    val kitDir: File,
)

class WorkloadStepExecutor(
    private val k8sService: K8sService,
    private val helmService: HelmService,
    private val kubectlService: KubectlService,
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
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
                        Event.Kit.StepFailed(
                            kit = context.kitName,
                            phase = phase,
                            stepType = stepType,
                            stepIndex = index,
                            error = error.message ?: error.toString(),
                        ),
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

            val host = ctx.controlHost.toHost()

            when (step) {
                is InstallStep.HelmRepo -> {
                    helmService.repoAdd(host = host, name = step.name, url = step.url)
                }

                is InstallStep.Helm -> {
                    val valuesFile =
                        if (step.valuesFile.isNotEmpty()) {
                            File(ctx.kitDir, interp(step.valuesFile))
                        } else {
                            null
                        }
                    helmService.upgradeInstall(
                        host = host,
                        release = interp(step.release),
                        chart = interp(step.chart),
                        namespace = interp(step.namespace),
                        version = step.version?.let { interp(it) },
                        values = step.values.mapKeys { (k, _) -> interp(k) }.mapValues { (_, v) -> interp(v) },
                        valuesFile = valuesFile,
                        createNamespace = true,
                    )
                }

                is InstallStep.HelmUninstall -> {
                    helmService.uninstall(
                        host = host,
                        release = interp(step.release),
                        namespace = interp(step.namespace),
                    )
                }

                is InstallStep.Namespace -> {
                    kubectlService.createNamespace(host = host, name = interp(step.name))
                }

                is InstallStep.Manifest -> {
                    val manifestFile = File(ctx.kitDir, interp(step.template))
                    require(manifestFile.isFile) {
                        "Manifest file not found: ${manifestFile.absolutePath}. Run 'install ${ctx.kitName}' first."
                    }
                    val content = if (step.interpolate) interp(manifestFile.readText()) else manifestFile.readText()
                    kubectlService.applyContent(host = host, yamlContent = content)
                }

                is InstallStep.ManifestUrl -> {
                    kubectlService.applyUrl(host = host, url = interp(step.url))
                }

                is InstallStep.Kustomize -> {
                    kubectlService.applyKustomize(host = host, url = interp(step.url))
                }

                is InstallStep.Wait -> {
                    kubectlService.wait(
                        host = host,
                        kind = interp(step.kind),
                        name = interp(step.name),
                        condition = interp(step.condition),
                        namespace = step.namespace?.let { interp(it) } ?: "default",
                        timeout = interp(step.timeout),
                    )
                }

                is InstallStep.Delete -> {
                    kubectlService.delete(
                        host = host,
                        kind = interp(step.kind),
                        name = interp(step.name),
                        namespace = step.namespace?.let { interp(it) } ?: "default",
                        ignoreNotFound = step.ignoreNotFound,
                    )
                }

                is InstallStep.PlatformPvs -> {
                    val serverType = ServerType.from(step.nodeType)
                    val targetHosts =
                        ctx.clusterState.hosts[serverType]
                            ?: error("No ${step.nodeType} nodes found in cluster state for platform-pvs step")
                    check(targetHosts.isNotEmpty()) { "No ${step.nodeType} nodes found in cluster state for platform-pvs step" }
                    val count = step.count ?: targetHosts.size
                    val storageSize =
                        ctx.variables["STORAGE_SIZE"] ?: error("STORAGE_SIZE variable required for platform-pvs step")
                    val dataPath = "${Constants.K8s.DB_MOUNT_PATH}/${ctx.kitName}"
                    // Ensure the local data directory exists on each target node before creating
                    // the PV objects. platform-pvs-delete removes the directory on uninstall;
                    // without this mkdir, a start after uninstall fails with "path does not exist"
                    // when the pod tries to mount the local PV.
                    for (targetHost in targetHosts) {
                        remoteOps.executeRemotely(host = targetHost.toHost(), command = "sudo mkdir -p $dataPath")
                        log.debug { "Ensured data directory $dataPath exists on ${targetHost.alias}" }
                    }
                    k8sService
                        .createLocalPersistentVolumes(
                            controlHost = ctx.controlHost,
                            config =
                                PersistentVolumeConfig(
                                    dbName = ctx.kitName,
                                    localPath = dataPath,
                                    count = count,
                                    storageSize = storageSize,
                                    storageClass = step.storageClass,
                                    namespace = Constants.K8s.NAMESPACE,
                                    volumeClaimTemplateName = step.volumeClaimTemplateName,
                                ),
                        ).getOrThrow()
                }

                is InstallStep.PlatformPvsDelete -> {
                    k8sService
                        .deleteLocalPersistentVolumes(
                            controlHost = ctx.controlHost,
                            dbName = ctx.kitName,
                        ).getOrThrow()
                    val dbHosts = ctx.clusterState.hosts[ServerType.from(step.nodeType)] ?: emptyList()
                    val dataPath = "${Constants.K8s.DB_MOUNT_PATH}/${ctx.kitName}"
                    for (clusterHost in dbHosts) {
                        remoteOps.executeRemotely(host = clusterHost.toHost(), command = "sudo rm -rf $dataPath")
                        log.info { "Deleted data directory $dataPath on ${clusterHost.alias}" }
                    }
                }

                is InstallStep.ConfigMap -> {
                    k8sService
                        .createConfigMap(
                            controlHost = ctx.controlHost,
                            namespace = interp(step.namespace),
                            name = interp(step.name),
                            data = step.data.mapValues { (_, v) -> interp(v) },
                        ).getOrThrow()
                }

                is InstallStep.Label -> {
                    val serverType = ServerType.from(step.nodeType)
                    val targetHosts =
                        ctx.clusterState.hosts[serverType]
                            ?: error("No ${step.nodeType} nodes found for label step")
                    for (host in targetHosts) {
                        k8sService
                            .labelNode(
                                controlHost = ctx.controlHost,
                                nodeName = host.alias,
                                labels = step.labels.mapValues { (_, v) -> interp(v) },
                            ).getOrThrow()
                    }
                }

                is InstallStep.Exec -> {
                    k8sService
                        .execInPod(
                            controlHost = ctx.controlHost,
                            namespace = interp(step.namespace),
                            podName = interp(step.pod),
                            command = step.command.map { interp(it) },
                        ).getOrThrow()
                }

                is InstallStep.Shell -> {
                    val tmpScript = Files.createTempFile("edl-step-", ".sh").toFile()
                    try {
                        tmpScript.writeText("#!/bin/bash\n${step.script}\n")
                        tmpScript.setExecutable(true)
                        val exitCode =
                            ProcessBuilder(tmpScript.absolutePath)
                                .directory(ctx.kitDir)
                                .inheritIO()
                                .also { pb -> pb.environment().putAll(ctx.variables) }
                                .start()
                                .waitFor()
                        check(exitCode == 0) { "Shell step exited with code $exitCode" }
                    } finally {
                        tmpScript.delete()
                    }
                }
            }
        }
}
