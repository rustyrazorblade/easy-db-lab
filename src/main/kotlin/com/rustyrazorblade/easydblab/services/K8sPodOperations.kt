package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.kubernetes.WorkloadPod

interface K8sPodOperations {
    fun execInPod(
        controlHost: ClusterHost,
        namespace: String,
        podName: String,
        command: List<String>,
    ): Result<String>

    fun listWorkloadPods(controlHost: ClusterHost): Result<List<WorkloadPod>>
}
