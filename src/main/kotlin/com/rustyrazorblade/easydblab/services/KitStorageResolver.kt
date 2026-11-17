package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus

/**
 * Resolves and checks the kit StorageClass while the cluster's API is reachable: at `init` against
 * a cluster the user brings (#1082), and at `up` when the operator passed `--kit-storage-class`.
 *
 * The class is the one the operator named, else the cluster's default class. Either must reclaim
 * with `Retain` ([StorageClassRetainCheck]); a refusal is emitted as an event and fails the command,
 * so nothing is recorded or created on a class that would delete kit data.
 */
class KitStorageResolver(
    private val k8sService: K8sService,
    private val eventBus: EventBus,
) {
    private val retainCheck = StorageClassRetainCheck(k8sService)

    /**
     * The kit StorageClass: [requested], or the cluster's default when null.
     *
     * @throws IllegalStateException when there is no class to use, or it does not retain
     */
    fun resolve(requested: String?): String {
        val storageClass = requested ?: defaultClass()
        retainCheck.requireKitClass(storageClass, eventBus)
        return storageClass
    }

    private fun defaultClass(): String {
        val default =
            k8sService.defaultStorageClass().getOrElse { exception ->
                error("Could not read the cluster's default StorageClass: ${exception.message}")
            }
        if (default == null) {
            val event = Event.Setup.NoDefaultKitStorageClass
            eventBus.emit(event)
            error(event.toDisplayString())
        }
        return default
    }
}
