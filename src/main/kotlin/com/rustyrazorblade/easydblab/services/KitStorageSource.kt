package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.KitStorage

/**
 * Resolves a cluster's [KitStorage], asking the cluster for its default StorageClass only where the
 * environment says kits claim on it: a cluster whose nodes easy-db-lab does not own, with no
 * recorded `--kit-storage-class`. On EC2 it never calls the cluster.
 *
 * The class is not checked here; the `kit-storage` step checks that it retains before any claim exists.
 */
class KitStorageSource(
    private val k8sService: K8sService,
) {
    /** The kit storage of [state]. */
    fun of(state: ClusterState): KitStorage = KitStorage.from(state, ::defaultClass)

    /** The cluster's default StorageClass, or null when it has none. */
    fun defaultClass(): String? =
        k8sService.defaultStorageClass().getOrElse { exception ->
            throw IllegalStateException("Could not read the cluster's default StorageClass: ${exception.message}", exception)
        }
}
