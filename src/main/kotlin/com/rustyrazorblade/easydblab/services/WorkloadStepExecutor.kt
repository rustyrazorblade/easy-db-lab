package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files

private val log = KotlinLogging.logger {}

fun interpolateStepVars(
    text: String,
    variables: Map<String, String>,
): String =
    text.replace(Regex("""\$\{(\w+)}""")) { match ->
        val key = match.groupValues[1]
        val resolved = variables[key]
        if (resolved == null) {
            log.warn { "Unresolved step variable: \${$key}" }
        }
        resolved ?: match.value
    }

class WorkloadStepExecutor(
    private val k8sService: K8sService,
    private val remoteOps: RemoteOperationsService,
    private val templateService: TemplateService,
    private val eventBus: EventBus,
) {
    fun execute(
        steps: List<InstallStep>,
        workloadName: String,
        phase: String,
        controlHost: ClusterHost,
        clusterState: ClusterState,
        variables: Map<String, String>,
        workloadDir: File,
    ): Result<Unit> =
        runCatching {
            steps.forEachIndexed { index, step ->
                val stepType = step::class.simpleName ?: "Unknown"
                eventBus.emit(
                    Event.Workload.StepStarted(
                        workload = workloadName,
                        phase = phase,
                        stepType = stepType,
                        stepIndex = index,
                    ),
                )
                executeStep(
                    step = step,
                    controlHost = controlHost,
                    clusterState = clusterState,
                    variables = variables,
                    workloadDir = workloadDir,
                    workloadName = workloadName,
                ).onFailure { error ->
                    eventBus.emit(
                        Event.Workload.StepFailed(
                            workload = workloadName,
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
        controlHost: ClusterHost,
        clusterState: ClusterState,
        variables: Map<String, String>,
        workloadDir: File,
        workloadName: String,
    ): Result<Unit> =
        runCatching {
            fun interp(s: String) = interpolateStepVars(s, variables)

            when (step) {
                is InstallStep.HelmRepo -> {
                    remoteOps.executeRemotely(
                        host = controlHost.toHost(),
                        command = "helm repo add ${step.name} ${step.url} 2>/dev/null || true && helm repo update",
                    )
                }

                is InstallStep.Helm -> {
                    val setArgs =
                        step.values.entries.joinToString(" ") { (k, v) ->
                            "--set ${interp(k)}=${interp(v)}"
                        }
                    val versionArg = step.version?.let { "--version $it" } ?: ""
                    val valuesArg =
                        if (step.valuesFile.isNotEmpty()) {
                            val localFile = File(workloadDir, interp(step.valuesFile))
                            val remoteTmp = "/tmp/edl-helm-values-$workloadName-${step.release}.yaml"
                            remoteOps.upload(
                                host = controlHost.toHost(),
                                local = localFile.toPath(),
                                remote = remoteTmp,
                            )
                            "--values $remoteTmp"
                        } else {
                            ""
                        }
                    remoteOps.executeRemotely(
                        host = controlHost.toHost(),
                        command =
                            listOf(
                                "helm upgrade --install",
                                interp(step.release),
                                interp(step.chart),
                                "--namespace ${interp(step.namespace)}",
                                "--create-namespace",
                                versionArg,
                                setArgs,
                                valuesArg,
                            ).filter { it.isNotBlank() }.joinToString(" "),
                    )
                    if (step.valuesFile.isNotEmpty()) {
                        val remoteTmp = "/tmp/edl-helm-values-$workloadName-${step.release}.yaml"
                        remoteOps.executeRemotely(host = controlHost.toHost(), command = "rm -f $remoteTmp")
                    }
                }

                is InstallStep.HelmUninstall -> {
                    remoteOps.executeRemotely(
                        host = controlHost.toHost(),
                        command = "helm uninstall ${interp(
                            step.release,
                        )} -n ${interp(step.namespace)} --ignore-not-found 2>/dev/null || true",
                    )
                }

                is InstallStep.Namespace -> {
                    remoteOps.executeRemotely(
                        host = controlHost.toHost(),
                        command = "kubectl create namespace ${interp(step.name)} --dry-run=client -o yaml | kubectl apply -f -",
                    )
                }

                is InstallStep.Manifest -> {
                    val manifestFile = File(workloadDir, interp(step.template))
                    require(manifestFile.isFile) {
                        "Manifest file not found: ${manifestFile.absolutePath}. Run 'install $workloadName' first."
                    }
                    k8sService.applyYaml(controlHost = controlHost, yamlContent = manifestFile.readText()).getOrThrow()
                }

                is InstallStep.ManifestUrl -> {
                    remoteOps.executeRemotely(
                        host = controlHost.toHost(),
                        command = "kubectl apply -f ${interp(step.url)}",
                    )
                }

                is InstallStep.Kustomize -> {
                    remoteOps.executeRemotely(
                        host = controlHost.toHost(),
                        command = "kubectl apply -k ${interp(step.url)}",
                    )
                }

                is InstallStep.Wait -> {
                    val nsArg = step.namespace?.let { "-n ${interp(it)}" } ?: ""
                    remoteOps.executeRemotely(
                        host = controlHost.toHost(),
                        command =
                            "kubectl wait --for=condition=${interp(step.condition)} " +
                                "${interp(step.kind)}/${interp(step.name)} " +
                                "$nsArg --timeout=${interp(step.timeout)}",
                    )
                }

                is InstallStep.Delete -> {
                    val nsArg = step.namespace?.let { "-n ${interp(it)}" } ?: ""
                    val ignoreArg = if (step.ignoreNotFound) "--ignore-not-found" else ""
                    remoteOps.executeRemotely(
                        host = controlHost.toHost(),
                        command = "kubectl delete ${interp(step.kind)}/${interp(step.name)} $nsArg $ignoreArg 2>/dev/null || true",
                    )
                }

                is InstallStep.PlatformPvs -> {
                    val serverType = if (step.nodeType == "app") ServerType.Stress else ServerType.Cassandra
                    val targetHosts =
                        clusterState.hosts[serverType]
                            ?: error("No ${step.nodeType} nodes found in cluster state for platform-pvs step")
                    check(targetHosts.isNotEmpty()) { "No ${step.nodeType} nodes found in cluster state for platform-pvs step" }
                    val count = step.count ?: targetHosts.size
                    val storageSize = variables["STORAGE_SIZE"] ?: error("STORAGE_SIZE variable required for platform-pvs step")
                    k8sService
                        .createLocalPersistentVolumes(
                            controlHost = controlHost,
                            config =
                                PersistentVolumeConfig(
                                    dbName = workloadName,
                                    localPath = "${Constants.K8s.DB_MOUNT_PATH}/$workloadName",
                                    count = count,
                                    storageSize = storageSize,
                                    storageClass = Constants.K8s.LOCAL_STORAGE_WFC_CLASS,
                                    namespace = Constants.K8s.NAMESPACE,
                                    volumeClaimTemplateName = "data",
                                ),
                        ).getOrThrow()
                }

                is InstallStep.ConfigMap -> {
                    k8sService
                        .createConfigMap(
                            controlHost = controlHost,
                            namespace = interp(step.namespace),
                            name = interp(step.name),
                            data = step.data.mapValues { (_, v) -> interp(v) },
                        ).getOrThrow()
                }

                is InstallStep.Label -> {
                    val serverType = if (step.nodeType == "app") ServerType.Stress else ServerType.Cassandra
                    val targetHosts =
                        clusterState.hosts[serverType]
                            ?: error("No ${step.nodeType} nodes found for label step")
                    for (host in targetHosts) {
                        k8sService
                            .labelNode(
                                controlHost = controlHost,
                                nodeName = host.alias,
                                labels = step.labels.mapValues { (_, v) -> interp(v) },
                            ).getOrThrow()
                    }
                }

                is InstallStep.Exec -> {
                    k8sService
                        .execInPod(
                            controlHost = controlHost,
                            namespace = interp(step.namespace),
                            podName = interp(step.pod),
                            command = step.command.map { interp(it) },
                        ).getOrThrow()
                }

                is InstallStep.Shell -> {
                    val envVars = variables.mapKeys { (k, _) -> k }
                    val tmpScript = Files.createTempFile("edl-step-", ".sh").toFile()
                    try {
                        tmpScript.writeText("#!/bin/sh\n${interp(step.script)}\n")
                        tmpScript.setExecutable(true)
                        val exitCode =
                            ProcessBuilder(tmpScript.absolutePath)
                                .directory(workloadDir)
                                .inheritIO()
                                .also { pb -> pb.environment().putAll(envVars) }
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
