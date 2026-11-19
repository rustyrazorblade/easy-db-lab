package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory
import com.rustyrazorblade.easydblab.kubernetes.currentContextOf
import com.rustyrazorblade.easydblab.services.K8sClientProvider
import com.rustyrazorblade.easydblab.services.WorkspaceKubeApiLocator
import java.io.File
import java.nio.file.Files

/**
 * Builds the production [K8sClientProvider] the way a command gets it: from the kubeconfig of a
 * cluster workspace, through the workspace [WorkspaceKubeApiLocator]. The workspace's state pins no
 * context, so the locator uses the kubeconfig's current-context. The integration tests give it the
 * kubeconfig of their K3s container, so the code under test opens its client exactly as it does on a
 * real cluster, with no mocked provider.
 */
object WorkspaceK8s {
    private const val STATE_FILE = "state.json"
    private const val PLACEHOLDER_CONTEXT = "placeholder"

    /**
     * A provider bound to a new workspace directory holding [kubeconfigYaml] as its kubeconfig.
     * [factory] turns the kubeconfig into a client; the default is the production factory. A test
     * whose [factory] ignores the kubeconfig may pass any text, such as `placeholder`.
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
        // A kubeconfig that names a current-context is located as `up` leaves it, through the fallback;
        // a placeholder for a factory that ignores it gets a pinned context, as `up` records one.
        val kubeconfig = File(workspace, Constants.K3s.LOCAL_KUBECONFIG).toPath()
        val pinned = if (runCatching { currentContextOf(kubeconfig) }.getOrNull() == null) PLACEHOLDER_CONTEXT else null
        val stateManager =
            ClusterStateManager(File(workspace, STATE_FILE)).apply { save(ClusterState(name = "k8s-it", kubeContext = pinned)) }
        File(workspace, STATE_FILE).deleteOnExit()
        return K8sClientProvider(WorkspaceKubeApiLocator(stateManager, workspace), factory)
    }
}
