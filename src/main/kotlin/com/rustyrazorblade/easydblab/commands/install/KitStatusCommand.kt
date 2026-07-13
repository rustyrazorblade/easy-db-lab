package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.kubernetes.KubernetesService
import com.rustyrazorblade.easydblab.services.HelmService
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitRuntime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import picocli.CommandLine.Command
import java.nio.file.Paths

private sealed interface KitRunningState {
    data class Running(
        val readyPods: Int,
        val totalPods: Int,
    ) : KitRunningState

    data object Stopped : KitRunningState

    data class Unknown(
        val reason: String,
    ) : KitRunningState
}

@RequiresProxy
@Command(name = "status")
class KitStatusCommand(
    private val kitName: String,
    private val installConfig: KitConfig,
) : PicoBaseCommand() {
    private val helmService: HelmService by inject()
    private val kubeconfigPath = Paths.get(context.workingDirectory.absolutePath, Constants.K3s.LOCAL_KUBECONFIG)
    private val kubeService: KubernetesService by inject { parametersOf(kubeconfigPath.toString()) }

    override fun execute() {
        val controlHost =
            clusterState.getControlHost()
                ?: run {
                    println("Status:    Unknown (no control node in cluster state)")
                    return
                }

        val state =
            if (installConfig.runtime != null) {
                checkRuntimeState(installConfig.runtime, kubeService, controlHost)
            } else {
                checkFallbackState(kubeService)
            }

        when (state) {
            is KitRunningState.Running ->
                println("Status:    Running (${state.readyPods}/${state.totalPods} pods ready)")
            is KitRunningState.Stopped ->
                println("Status:    Stopped")
            is KitRunningState.Unknown ->
                println("Status:    Unknown (${state.reason})")
        }

        if (state is KitRunningState.Running) {
            printEndpoints()
        }
    }

    private fun checkRuntimeState(
        runtime: KitRuntime,
        kubeService: KubernetesService,
        controlHost: ClusterHost,
    ): KitRunningState {
        val namespace = runtime.namespace
        return when (runtime.type) {
            KitRuntime.RuntimeType.HELM -> {
                val release = runtime.release.ifBlank { kitName }
                if (!helmService.releaseExists(
                        host = controlHost.toHost(),
                        release = release,
                        namespace = namespace,
                    )
                ) {
                    return KitRunningState.Stopped
                }
                val podSelector =
                    runtime.selector
                        .replace("\${KIT_NAME}", kitName)
                        .ifBlank { "app.kubernetes.io/instance=$release" }
                val pods =
                    kubeService
                        .listPodsByLabel(podSelector, namespace)
                        .getOrElse { emptyList() }
                KitRunningState.Running(readyPods = countReadyPods(pods), totalPods = pods.size)
            }
            KitRuntime.RuntimeType.DEPLOYMENT,
            KitRuntime.RuntimeType.STATEFULSET,
            KitRuntime.RuntimeType.PODS,
            -> {
                val selector =
                    runtime.selector
                        .replace("\${KIT_NAME}", kitName)
                        .ifBlank { "app.kubernetes.io/name=$kitName" }
                val pods = kubeService.listPodsByLabel(selector, namespace).getOrElse { emptyList() }
                if (pods.isEmpty()) {
                    KitRunningState.Stopped
                } else {
                    KitRunningState.Running(readyPods = countReadyPods(pods), totalPods = pods.size)
                }
            }
        }
    }

    private fun checkFallbackState(kubeService: KubernetesService): KitRunningState {
        val pods =
            kubeService
                .listPodsByLabel("app.kubernetes.io/name=$kitName", "default")
                .getOrElse { return KitRunningState.Unknown("K8s query failed") }
        return if (pods.isEmpty()) {
            KitRunningState.Stopped
        } else {
            KitRunningState.Running(readyPods = countReadyPods(pods), totalPods = pods.size)
        }
    }

    private fun countReadyPods(pods: List<KubernetesPod>): Int =
        pods.count { pod ->
            val parts = pod.ready.split("/")
            parts.size == 2 && parts[0] == parts[1] && parts[0] != "0" && pod.status == "Running"
        }

    private fun printEndpoints() {
        for (endpoint in installConfig.endpoints) {
            val ips = resolveIps(endpoint.nodeType)
            for (ip in ips) {
                println("  %-20s  %-8s  %s".format(endpoint.name, endpoint.type.name.lowercase(), endpoint.formatUrl(ip)))
            }
        }
    }

    private fun resolveIps(nodeType: String): List<String> {
        val serverType =
            runCatching { ServerType.from(nodeType.lowercase()) }
                .getOrElse {
                    log.warn { "Unknown node-type '$nodeType' in endpoint for $kitName" }
                    return emptyList()
                }
        return clusterState.hosts[serverType]?.map { it.privateIp } ?: emptyList()
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
