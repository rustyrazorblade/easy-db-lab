package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.KubeApiLocator
import com.rustyrazorblade.easydblab.kubernetes.currentContextOf
import java.io.File

/**
 * Resolves the cluster's [KubeApi] from the workspace: the kubeconfig at `<workspace>/kubeconfig` and
 * the context pinned in cluster state (`ClusterState.kubeContext`), falling back to the kubeconfig's
 * `current-context` when the workspace records none.
 *
 * This is the only [KubeApiLocator] today; #1040's `Environment` later produces the [KubeApi] and this
 * becomes a thin adapter. It fails fast when the workspace has no kubeconfig, since every K8s command
 * must run from the cluster's workspace.
 */
class WorkspaceKubeApiLocator(
    private val clusterStateManager: ClusterStateManager,
    private val workspace: File,
) : KubeApiLocator {
    override fun locate(): KubeApi {
        val kubeconfigFile = File(workspace, Constants.K3s.LOCAL_KUBECONFIG)
        check(kubeconfigFile.exists()) {
            "No kubeconfig at ${kubeconfigFile.absolutePath}. Run the command from the cluster's workspace."
        }
        val kubeconfigPath = kubeconfigFile.toPath()
        val context =
            clusterStateManager.load().kubeContext
                ?: currentContextOf(kubeconfigPath)
                ?: error(
                    "No context pinned in cluster state and kubeconfig " +
                        "${kubeconfigFile.absolutePath} names no current-context",
                )
        return KubeApi(kubeconfigPath, context)
    }
}
