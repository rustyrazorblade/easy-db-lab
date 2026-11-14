package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.kubernetes.WorkloadPod

interface K8sPodOperations {
    fun execInPod(
        namespace: String,
        podName: String,
        command: List<String>,
    ): Result<String>

    fun listWorkloadPods(): Result<List<WorkloadPod>>
}
