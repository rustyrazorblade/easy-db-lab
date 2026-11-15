package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.KubeApiLocator
import com.rustyrazorblade.easydblab.kubernetes.currentContextOf
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the cluster's [KubeApi] from the workspace: the kubeconfig at `<workspace>/kubeconfig` and
 * the context pinned in cluster state (`ClusterState.kubeContext`), falling back to the kubeconfig's
 * `current-context` when the workspace records none. The fallback is logged with the context it chose
 * and the kubeconfig path, once per (path, context) pair, so a cluster reached through an unpinned
 * context is visible in the log without a line on every K8s operation of a long-running `server`.
 *
 * This is the only [KubeApiLocator] today; #1040's `Environment` later produces the [KubeApi] and this
 * becomes a thin adapter. It fails fast when the workspace has no kubeconfig, since every K8s command
 * must run from the cluster's workspace.
 */
class WorkspaceKubeApiLocator(
    private val clusterStateManager: ClusterStateManager,
    private val workspace: File,
) : KubeApiLocator {
    /** The (kubeconfig path, context) fallbacks already logged; locate() runs on every K8s operation. */
    private val loggedFallbacks: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet()

    override fun locate(): KubeApi {
        val kubeconfigFile = File(workspace, Constants.K3s.LOCAL_KUBECONFIG)
        check(kubeconfigFile.exists()) {
            "No kubeconfig at ${kubeconfigFile.absolutePath}. Run the command from the cluster's workspace."
        }
        val kubeconfigPath = kubeconfigFile.toPath()
        val context = clusterStateManager.load().kubeContext ?: fallbackContext(kubeconfigFile)
        return KubeApi(kubeconfigPath, context)
    }

    private fun fallbackContext(kubeconfigFile: File): String {
        val current =
            currentContextOf(kubeconfigFile.toPath())
                ?: error(
                    "No context pinned in cluster state and kubeconfig " +
                        "${kubeconfigFile.absolutePath} names no current-context",
                )
        if (loggedFallbacks.add(kubeconfigFile.absolutePath to current)) {
            log.info {
                "No context pinned in cluster state; using current-context '$current' of kubeconfig " +
                    kubeconfigFile.absolutePath
            }
        }
        return current
    }

    private companion object {
        private val log = KotlinLogging.logger {}
    }
}
