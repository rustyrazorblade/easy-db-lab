package com.rustyrazorblade.easydblab.architecture.fixtures

import com.rustyrazorblade.easydblab.services.K8sService

/**
 * A class that depends on the Kubernetes service and takes no
 * [com.rustyrazorblade.easydblab.configuration.ClusterHost]. The architecture test allow-lists it to
 * prove that an allow-list entry which no longer takes a host is reported as stale.
 */
@Suppress("unused")
class K8sServiceDependentWithoutHost(
    private val k8sService: K8sService,
) {
    fun doWork(): String = k8sService.hashCode().toString()
}
