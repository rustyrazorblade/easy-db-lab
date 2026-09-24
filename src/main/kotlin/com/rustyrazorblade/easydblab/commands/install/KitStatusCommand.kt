package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.kubernetes.KubernetesService
import com.rustyrazorblade.easydblab.services.HelmService
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitEndpointAddresses
import com.rustyrazorblade.easydblab.services.KitRuntime
import com.rustyrazorblade.easydblab.services.podSelector
import com.rustyrazorblade.easydblab.services.withKitName
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
                val podSelector = withKitName(runtime.selector, kitName).ifBlank { "app.kubernetes.io/instance=$release" }
                stateOfPods(kubeService, podSelector, namespace) { pods ->
                    KitRunningState.Running(readyPods = countReadyPods(pods), totalPods = pods.size)
                }
            }
            KitRuntime.RuntimeType.DEPLOYMENT,
            KitRuntime.RuntimeType.STATEFULSET,
            KitRuntime.RuntimeType.PODS,
            -> stateOfPods(kubeService, podSelector(kitName, runtime), namespace, ::runningOrStopped)
        }
    }

    private fun checkFallbackState(kubeService: KubernetesService): KitRunningState =
        stateOfPods(kubeService, podSelector(kitName, runtime = null), "default", ::runningOrStopped)

    /**
     * Lists the kit's pods and derives its state from them. A failed query is
     * [KitRunningState.Unknown] carrying the cause — never an empty list, which would
     * report a kit we could not see as stopped.
     */
    private fun stateOfPods(
        kubeService: KubernetesService,
        selector: String,
        namespace: String,
        fromPods: (List<KubernetesPod>) -> KitRunningState,
    ): KitRunningState =
        kubeService.listPodsByLabel(selector, namespace).fold(
            onSuccess = fromPods,
            onFailure = { e ->
                log.warn(e) { "Failed to list pods for kit $kitName ($selector in $namespace)" }
                KitRunningState.Unknown("K8s query failed: ${e.message}")
            },
        )

    private fun runningOrStopped(pods: List<KubernetesPod>): KitRunningState =
        if (pods.isEmpty()) {
            KitRunningState.Stopped
        } else {
            KitRunningState.Running(readyPods = countReadyPods(pods), totalPods = pods.size)
        }

    private fun countReadyPods(pods: List<KubernetesPod>): Int =
        pods.count { pod ->
            val parts = pod.ready.split("/")
            parts.size == 2 && parts[0] == parts[1] && parts[0] != "0" && pod.status == "Running"
        }

    private fun printEndpoints() {
        val resolved = KitEndpointAddresses.resolve(installConfig.endpoints, clusterState.hosts)
        if (resolved.isNotEmpty()) println(KitEndpointAddresses.formatLines(resolved))
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
