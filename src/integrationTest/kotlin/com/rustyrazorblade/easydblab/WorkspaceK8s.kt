package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory
import com.rustyrazorblade.easydblab.services.K8sClientProvider
import java.io.File
import java.nio.file.Files

/**
 * Builds the production [K8sClientProvider] the way a command gets it: from the kubeconfig of a
 * cluster workspace, through [Context.workingDirectory]. The integration tests give it the
 * kubeconfig of their K3s container, so the code under test opens its client exactly as it does on a
 * real cluster, with no mocked provider.
 */
object WorkspaceK8s {
    /**
     * A provider bound to a new workspace directory holding [kubeconfigYaml] as its kubeconfig.
     * [factory] turns the kubeconfig into a client; the default is the production factory.
     */
    fun provider(
        kubeconfigYaml: String,
        factory: KubernetesClientFactory = ProxiedKubernetesClientFactory(),
    ): K8sClientProvider {
        val workspace = Files.createTempDirectory("k8s-workspace").toFile().apply { deleteOnExit() }
        File(workspace, Constants.K3s.LOCAL_KUBECONFIG).apply {
            writeText(kubeconfigYaml)
            deleteOnExit()
        }
        return K8sClientProvider(Context(easyDbLabUserDirectory = workspace, workingDirectory = workspace), factory)
    }
}
