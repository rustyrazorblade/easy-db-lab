package com.rustyrazorblade.easydblab.kubernetes

import java.nio.file.Path

/**
 * Where the Kubernetes API is: the kubeconfig file and the context pinned in it.
 *
 * This is the cluster contract's `api` field — the only thing a K8s client needs to reach the
 * cluster. It replaces the control-host coupling: the client connects to the API server named by
 * [context] in [kubeconfig], with no knowledge of the node easy-db-lab provisioned.
 */
data class KubeApi(
    val kubeconfig: Path,
    val context: String,
)
