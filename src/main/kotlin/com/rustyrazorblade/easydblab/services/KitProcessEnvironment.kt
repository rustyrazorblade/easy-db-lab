package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CLUSTER_STATE
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import java.io.File

/**
 * Prepares the environment of every process a kit runs on the operator's machine: `type: shell`
 * steps in `install:` and in phases, phase scripts, and hook scripts.
 *
 * Every one of them gets the same things, so a kit reaches the cluster the same way wherever its
 * code runs:
 * - `<workspace>/bin` first on `PATH`, with the tool wrappers written there before the process
 *   starts, so `kubectl`, `helm`, `curl` and the other wrapped tools go through the SOCKS tunnel
 *   (or straight to the nodes on a Tailscale cluster), indirect calls included;
 * - `KUBECONFIG` set to the absolute path of the workspace kubeconfig, used as it is. Shell steps run
 *   from the kit directory, where the relative `kubeconfig` from [TemplateVariables] points at
 *   nothing;
 * - `EDL_KIT_LIB` set to the absolute path of the shared kit shell library in `<workspace>/bin`,
 *   which kit scripts source;
 * - `EDL_NODE_SSH` set to `true` when the cluster has the `NODE_SSH` capability, so the workspace `ssh`
 *   wrapper can reach the nodes, and `false` otherwise (a local or bring-your-own Kubernetes cluster).
 *   It is read from the capability set the workspace records, never from whether an `sshConfig`
 *   exists: an override `node-ssh=false` gives `false` even where one does. A workspace that records
 *   no set (or has no `state.json`) reads as AWS, so `true`. A kit skips its node checks over SSH on
 *   `false`, and never guesses from a failed `ssh`.
 *
 * @param installer writes the wrappers into the workspace
 */
class KitProcessEnvironment(
    private val installer: ToolWrapperInstaller,
) {
    /**
     * Applies the kit process environment to [builder] for [workspace], on top of [variables].
     *
     * @return [builder], for chaining
     * @throws IllegalStateException if the workspace kubeconfig does not exist, before anything is written
     */
    fun applyTo(
        builder: ProcessBuilder,
        workspace: File,
        variables: Map<String, String>,
    ): ProcessBuilder {
        val kubeconfig = File(workspace, Constants.K3s.LOCAL_KUBECONFIG).absoluteFile
        check(kubeconfig.isFile) {
            "The workspace kubeconfig ${kubeconfig.path} does not exist, so a kit process cannot reach the cluster. " +
                "Run this command from the cluster's workspace after 'easy-db-lab up'."
        }
        installer.install(workspace)
        val environment = builder.environment()
        val inheritedPath = environment["PATH"]
        environment.putAll(variables)
        environment["KUBECONFIG"] = kubeconfig.path
        val bin = File(workspace, Constants.ToolWrappers.DIRECTORY).absoluteFile
        environment[Constants.Kit.LIBRARY_ENV] = File(bin, Constants.ToolWrappers.KIT_LIBRARY).path
        environment[Constants.Kit.NODE_SSH_ENV] = (NodeCapability.NODE_SSH in capabilitiesOf(workspace)).toString()
        environment["PATH"] = listOfNotNull(bin.path, inheritedPath).joinToString(File.pathSeparator)
        return builder
    }

    /** The capabilities [workspace] records; AWS when it has no cluster state. */
    private fun capabilitiesOf(workspace: File): NodeCapabilities {
        val stateFile = File(workspace, CLUSTER_STATE)
        return when {
            stateFile.isFile -> NodeCapabilities.from(ClusterStateManager(stateFile).load())
            else -> NodeCapabilities.AWS
        }
    }
}
