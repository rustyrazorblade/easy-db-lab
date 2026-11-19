package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.Constants
import java.io.File

/**
 * The one place that names a cluster workspace's kubeconfig.
 *
 * `up` writes the kubeconfig into the workspace, and everything that reaches the cluster reads it
 * from there: the Fabric8 client ([com.rustyrazorblade.easydblab.services.K8sClientProvider]), the
 * environment of kit processes, the metrics stream and the status views. Resolving it here, against
 * the workspace directory a command was given, keeps those callers on the same cluster whatever
 * directory the process started in.
 */
object WorkspaceKubeconfig {
    /** The absolute kubeconfig path for [workspace]. The file may not exist yet. */
    fun file(workspace: File): File = File(workspace, Constants.K3s.LOCAL_KUBECONFIG).absoluteFile

    /** True when [workspace] holds a kubeconfig, which means `up` has set up K3s. */
    fun exists(workspace: File): Boolean = file(workspace).isFile

    /**
     * The kubeconfig of [workspace], which must exist.
     *
     * @throws IllegalStateException with the absolute path when the file is missing
     */
    fun require(workspace: File): File {
        val kubeconfig = file(workspace)
        check(kubeconfig.isFile) {
            "kubeconfig not found at $kubeconfig. Run this from the cluster workspace, after 'up' has written it."
        }
        return kubeconfig
    }
}
