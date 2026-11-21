package com.rustyrazorblade.easydblab.services

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.providers.ssh.installFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files

/**
 * The K3s config drop-in that lets a node's kubelet admit the pod network sysctls
 * (pod-network-tuning, design D3).
 *
 * Kubernetes classes some of the per-namespace network sysctls as unsafe, and the kubelet refuses
 * a pod that carries one (`SysctlForbidden`) unless it is in `--allowed-unsafe-sysctls`. The
 * drop-in lists every injected key, safe ones too, so a pod stays admitted if a key ever moves out
 * of the safe set. It is K3s bootstrap: the server and the agent install it before their start
 * script runs, on every run, so the kubelet reads it on its first start. A cluster whose nodes the
 * tool does not bootstrap configures its kubelets itself.
 */
object KubeletSysctlsDropIn {
    private val yaml = Yaml(configuration = YamlConfiguration(encodeDefaults = false))

    /** The drop-in's YAML: `kubelet-arg+`, so it adds to the eviction and limits args. */
    fun render(): String =
        yaml.encodeToString(
            KubeletSysctlsConfig.serializer(),
            KubeletSysctlsConfig(listOf("allowed-unsafe-sysctls=${PodNetworkSysctls.packaged().keys.joinToString(",")}")),
        )

    /** Writes [render] to [Constants.NodeTuning.KUBELET_SYSCTLS_DROP_IN] on [host]. */
    fun install(
        remoteOps: RemoteOperationsService,
        host: Host,
    ) {
        val local = Files.createTempFile("edl-kubelet-sysctls-", ".yaml")
        try {
            Files.writeString(local, render())
            remoteOps.executeRemotely(
                host,
                "sudo mkdir -p ${Constants.NodeTuning.KUBELET_SYSCTLS_DROP_IN.substringBeforeLast('/')}",
                output = false,
            )
            remoteOps.installFile(host, local, Constants.NodeTuning.KUBELET_SYSCTLS_DROP_IN, "0644")
        } finally {
            Files.deleteIfExists(local)
        }
    }
}

/** The kubelet args of [KubeletSysctlsDropIn], serialized to a K3s config drop-in. */
@Serializable
data class KubeletSysctlsConfig(
    @SerialName("kubelet-arg+")
    val kubeletArgs: List<String>,
)
