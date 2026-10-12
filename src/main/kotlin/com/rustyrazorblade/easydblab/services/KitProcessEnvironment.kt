package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import java.io.File

/**
 * Prepares the environment of every process a kit runs on the operator's machine: `type: shell`
 * steps in `install:` and in phases, phase scripts, and hook scripts.
 *
 * Every one of them gets the same two things, so a kit reaches the cluster the same way wherever its
 * code runs:
 * - `<workspace>/bin` first on `PATH`, with the tool wrappers written there before the process
 *   starts, so `kubectl`, `helm`, `curl` and the other wrapped tools go through the SOCKS tunnel
 *   (or straight to the nodes on a Tailscale cluster), indirect calls included;
 * - `KUBECONFIG` set to the absolute path of the workspace kubeconfig, used as it is. Shell steps run
 *   from the kit directory, where the relative `kubeconfig` from [TemplateVariables] points at
 *   nothing.
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
        val bin = File(workspace, Constants.ToolWrappers.DIRECTORY).absolutePath
        environment["PATH"] = listOfNotNull(bin, inheritedPath).joinToString(File.pathSeparator)
        return builder
    }
}
