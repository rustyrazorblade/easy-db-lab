package com.rustyrazorblade.easydblab.kubernetes

import java.time.Duration

/**
 * Represents a Kubernetes Job
 *
 * @property namespace The namespace the job is in
 * @property name The name of the job
 * @property status The current status (e.g., "Running", "Completed", "Failed")
 * @property completions Completion status string (e.g., "1/1", "0/3")
 * @property age How long ago the job was created
 */
data class KubernetesJob(
    val namespace: String,
    val name: String,
    val status: String,
    val completions: String,
    val age: Duration,
)

/**
 * Represents a Kubernetes Pod
 *
 * @property namespace The namespace the pod is in
 * @property name The name of the pod
 * @property status The current status (e.g., "Running", "Pending", "Succeeded")
 * @property ready Ready status string (e.g., "1/1", "0/1")
 * @property restarts Number of container restarts
 * @property age How long ago the pod was created
 */
data class KubernetesPod(
    val namespace: String,
    val name: String,
    val status: String,
    val ready: String,
    val restarts: Int,
    val age: Duration,
)

/**
 * Represents a workload pod (pod in a non-system namespace)
 */
data class WorkloadPod(
    val namespace: String,
    val name: String,
    val nodeName: String,
    val ready: String,
    val status: String,
)

/**
 * Represents a Kubernetes Node
 *
 * @property name The name of the node
 * @property status The current status (e.g., "Ready", "NotReady")
 * @property roles List of roles assigned to the node
 * @property version The Kubernetes version running on the node
 */
data class KubernetesNode(
    val name: String,
    val status: String,
    val roles: List<String>,
    val version: String,
)
