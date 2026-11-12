package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost

/**
 * Grants a workload permission to pull its image from a private registry, without the caller
 * knowing which cloud's registry it is.
 *
 * Lives in `services/` so the workloads that may run a custom image ([SidecarService],
 * [StressJobService]) depend on this abstraction, and the cloud-specific implementation (ECR on
 * AWS) stays in the provider's service package with its SDK client.
 */
interface ImagePullSecretService {
    /**
     * Ensures a usable pull secret exists in [namespace] for [image], returning the secret's name,
     * or an empty string when the image needs none.
     */
    fun ensureFor(
        controlHost: ClusterHost,
        image: String,
        namespace: String,
    ): String
}
