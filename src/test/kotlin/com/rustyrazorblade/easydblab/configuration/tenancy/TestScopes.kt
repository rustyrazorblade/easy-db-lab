package com.rustyrazorblade.easydblab.configuration.tenancy

import com.rustyrazorblade.easydblab.Constants

/**
 * The [InstallScope]s tests render and apply with. [EC2] is the scope a cluster easy-db-lab builds
 * resolves to when `init` records no namespace, so a test that pins today's AWS manifests uses it.
 */
object TestScopes {
    /** The cluster id every [EC2] resource is labelled and suffixed with. */
    const val CLUSTER_ID = "test-cluster-id"

    /** The scope of an EC2 cluster with no recorded namespace: `default`. */
    val EC2 = InstallScope(K8sNamespace(Constants.ContractDefaults.OWNED_NAMESPACE), CLUSTER_ID)

    /** The [EC2] scope's namespace, as the string the Kubernetes API takes. */
    val NAMESPACE: String = EC2.namespace.value
}
