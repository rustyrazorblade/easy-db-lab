package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.BackendStorage

/**
 * Refuses backend state on a StorageClass that would delete it.
 *
 * Off owned nodes each backend keeps its state on a claim ([BackendStorage.RetainedClaim]). A class
 * whose `reclaimPolicy` is `Delete` (the Kubernetes default) removes the volume, and with it
 * Pyroscope's metastore and any unflushed samples, once the claim goes away. That is automatic
 * deletion of the owner's data, so the deploy reads the class first and fails before it applies
 * anything unless the class reclaims with `Retain` ([StorageClassRetainCheck]).
 */
class RetainedClaimCheck(
    k8sService: K8sService,
) {
    private val retainCheck = StorageClassRetainCheck(k8sService)

    /** Fails unless [storage] keeps its volumes: a host path, or a claim on a `Retain` class. */
    fun require(storage: BackendStorage) {
        when (storage) {
            BackendStorage.HostPath -> Unit
            is BackendStorage.RetainedClaim -> retainCheck.require(storage.storageClass, RetainedData.BackendState)
        }
    }
}
