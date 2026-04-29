package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost

interface K8sPodOperations {
    fun execInPod(
        controlHost: ClusterHost,
        namespace: String,
        podName: String,
        command: List<String>,
    ): Result<String>
}
