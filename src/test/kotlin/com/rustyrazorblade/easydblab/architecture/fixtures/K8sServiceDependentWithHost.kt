package com.rustyrazorblade.easydblab.architecture.fixtures

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.services.K8sService

/**
 * A deliberate violation for
 * [com.rustyrazorblade.easydblab.architecture.NoClusterHostInK8sServicesTest]: a class that depends
 * on the Kubernetes service and takes a [ClusterHost] parameter. The test imports it alone to prove
 * the rule fails on a reintroduced control-host parameter.
 */
@Suppress("unused")
class K8sServiceDependentWithHost(
    private val k8sService: K8sService,
) {
    fun doWork(controlHost: ClusterHost): String = "${k8sService.hashCode()}:${controlHost.alias}"
}
