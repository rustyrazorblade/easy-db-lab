package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.BackendStorage

/**
 * Refuses backend state on a StorageClass that would delete it.
 *
 * Off owned nodes each backend keeps its state on a claim ([BackendStorage.RetainedClaim]). A class
 * whose `reclaimPolicy` is `Delete` (the Kubernetes default) removes the volume, and with it
 * Pyroscope's metastore and any unflushed samples, once the claim goes away. That is automatic
 * deletion of the owner's data, so the deploy reads the class first and fails before it applies
 * anything unless the class reclaims with `Retain`.
 */
class RetainedClaimCheck(
    private val k8sService: K8sService,
) {
    /** Fails unless [storage] keeps its volumes: a host path, or a claim on a `Retain` class. */
    fun require(storage: BackendStorage) {
        when (storage) {
            BackendStorage.HostPath -> Unit
            is BackendStorage.RetainedClaim -> requireRetain(storage.storageClass)
        }
    }

    private fun requireRetain(storageClass: String) {
        val policy =
            k8sService.storageClassReclaimPolicy(storageClass).getOrElse { exception ->
                error("Could not read StorageClass $storageClass for the backends' state: ${exception.message}")
            }
        checkNotNull(policy) {
            "StorageClass $storageClass for the backends' state does not exist; create it with reclaimPolicy Retain."
        }
        check(policy == RETAIN) {
            "StorageClass $storageClass reclaims with $policy, which would delete the backends' state with its claim. " +
                "Use a StorageClass with reclaimPolicy $RETAIN."
        }
    }

    private companion object {
        const val RETAIN = "Retain"
    }
}
