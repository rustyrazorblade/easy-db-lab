package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.oshai.kotlinlogging.KotlinLogging

interface CiliumInstallService {
    fun install(controlHost: Host): Result<Unit>
}

class DefaultCiliumInstallService(
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
) : CiliumInstallService {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val CILIUM_HELM_REPO_NAME = "cilium"
        private const val CILIUM_HELM_REPO_URL = "https://helm.cilium.io/"
        private const val CILIUM_RELEASE_NAME = "cilium"
        private const val CILIUM_CHART = "cilium/cilium"
        private const val CILIUM_NAMESPACE = "kube-system"
        private const val HELM_TIMEOUT = "5m"
    }

    override fun install(controlHost: Host): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Cilium.Installing)

            eventBus.emit(Event.Cilium.RepoAdding)
            remoteOps.executeRemotely(
                controlHost,
                "helm repo add $CILIUM_HELM_REPO_NAME $CILIUM_HELM_REPO_URL 2>/dev/null || true && helm repo update $CILIUM_HELM_REPO_NAME",
            )
            log.info { "Cilium helm repo added/updated on ${controlHost.alias}" }

            eventBus.emit(Event.Cilium.InstallingChart)
            remoteOps.executeRemotely(
                controlHost,
                buildHelmInstallCommand(),
            )
            log.info { "Cilium installed successfully on ${controlHost.alias}" }

            eventBus.emit(Event.Cilium.Installed)
        }.onFailure { e ->
            log.error(e) { "Failed to install Cilium on ${controlHost.alias}" }
            eventBus.emit(Event.Cilium.InstallFailed(e.message ?: "unknown error"))
        }

    private fun buildHelmInstallCommand(): String =
        listOf(
            "helm upgrade --install $CILIUM_RELEASE_NAME $CILIUM_CHART",
            "--namespace $CILIUM_NAMESPACE",
            "--set kubeProxyReplacement=true",
            "--set hubble.relay.enabled=true",
            "--set hubble.ui.enabled=true",
            "--set 'hubble.metrics.enabled={dns,drop,tcp,flow,port-distribution,icmp,http}'",
            "--wait",
            "--timeout $HELM_TIMEOUT",
        ).joinToString(" ")
}
