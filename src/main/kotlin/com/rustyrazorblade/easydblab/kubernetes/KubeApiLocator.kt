package com.rustyrazorblade.easydblab.kubernetes

import java.nio.file.Path

/**
 * Resolves the cluster's [KubeApi] for the current command.
 *
 * It is environment-neutral: every execution environment (EC2 + K3s, K3s in Docker, a brought
 * kubeconfig) reaches its cluster through the workspace kubeconfig, so one implementation serves all
 * of them. Resolving once per command keeps the control node's identity out of every K8s service.
 */
fun interface KubeApiLocator {
    fun locate(): KubeApi
}

/**
 * The kubeconfig's `current-context`, or null when it names none.
 *
 * Used as the fallback when the workspace has recorded no context. Reads the file with Fabric8's
 * typed parser rather than diving into untyped YAML.
 */
fun currentContextOf(kubeconfig: Path): String? = parseKubeconfig(kubeconfig).currentContext?.takeIf { it.isNotBlank() }
