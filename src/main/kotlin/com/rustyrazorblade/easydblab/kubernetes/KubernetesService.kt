package com.rustyrazorblade.easydblab.kubernetes

/**
 * High-level service interface for Kubernetes operations.
 *
 * Provides business-level operations for interacting with a Kubernetes cluster.
 * Implementations handle connection management and error handling.
 */
interface KubernetesService {
    /**
     * List jobs in the cluster.
     *
     * @param namespace Optional namespace to filter jobs. If null, lists jobs across all namespaces.
     * @return Result containing list of jobs or an error
     */
    fun listJobs(namespace: String? = null): Result<List<KubernetesJob>>

    /**
     * List pods in the cluster.
     *
     * @param namespace Optional namespace to filter pods. If null, lists pods across all namespaces.
     * @return Result containing list of pods or an error
     */
    fun listPods(namespace: String? = null): Result<List<KubernetesPod>>

    /**
     * Get all nodes in the cluster.
     *
     * @return Result containing list of nodes or an error
     */
    fun getNodes(): Result<List<KubernetesNode>>

    /**
     * List pods matching a label selector in the given namespace.
     *
     * @param labelSelector Kubernetes label selector string (e.g. "app.kubernetes.io/name=presto")
     * @param namespace Namespace to search in (defaults to "default")
     * @return Result containing matching pods or an error
     */
    fun listPodsByLabel(
        labelSelector: String,
        namespace: String = "default",
    ): Result<List<KubernetesPod>>

    /**
     * Check if the Kubernetes API is reachable.
     *
     * @return Result indicating success or connection failure
     */
    fun isReachable(): Result<Boolean>
}
