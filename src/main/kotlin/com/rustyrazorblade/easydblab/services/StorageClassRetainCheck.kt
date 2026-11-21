package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus

/**
 * What a claim on a checked StorageClass holds, for the refusal: what a `Delete` class would lose,
 * and how the operator fixes it.
 */
enum class RetainedData {
    /** A kit's data, on the class every kit claim names (`init --kit-storage-class`). */
    KitData {
        override fun refusal(
            storageClass: String,
            problem: String,
        ) = "StorageClass $storageClass $problem. Pass init --kit-storage-class naming a StorageClass with " +
            "reclaimPolicy: Retain, or create one (a copy of $storageClass with reclaimPolicy: Retain)."

        override val loss = "kit data when a kit's claim is deleted"
    },

    /** An observability backend's state, on its `<workload>-data` claim. */
    BackendState {
        override fun refusal(
            storageClass: String,
            problem: String,
        ) = "StorageClass $storageClass for the backends' state $problem. Use a StorageClass with reclaimPolicy Retain."

        override val loss = "the backends' state with its claim"
    },
    ;

    /** The refusal for [storageClass], whose [problem] is that it is missing or deletes. */
    abstract fun refusal(
        storageClass: String,
        problem: String,
    ): String

    /** What a deleting class would delete. */
    abstract val loss: String
}

/**
 * Refuses a StorageClass that would delete what its claims hold.
 *
 * A class whose `reclaimPolicy` is `Delete` (the Kubernetes default) removes the volume once its
 * claim goes away. For a kit's data or a backend's state that is automatic deletion of the owner's
 * data, so callers read the class before anything claims from it and fail unless it reclaims with
 * `Retain`. A class that does not exist is refused the same way, since a claim on it never binds.
 */
class StorageClassRetainCheck(
    private val k8sService: K8sService,
) {
    /**
     * Fails unless [storageClass] exists and reclaims with `Retain`; [holds] words the refusal.
     *
     * @throws StorageClassRefusedException when the class is missing or does not retain
     */
    fun require(
        storageClass: String,
        holds: RetainedData,
    ) {
        val policy =
            k8sService.storageClassReclaimPolicy(storageClass).getOrElse { exception ->
                throw IllegalStateException("Could not read StorageClass $storageClass: ${exception.message}", exception)
            }
        if (policy == RETAIN) return
        val problem = if (policy == null) "does not exist" else "reclaims with $policy, which would delete ${holds.loss}"
        throw StorageClassRefusedException(storageClass, policy, holds.refusal(storageClass, problem))
    }

    private companion object {
        const val RETAIN = "Retain"
    }
}

/**
 * A StorageClass [StorageClassRetainCheck] refused: [storageClass] with [reclaimPolicy], or null when
 * the class does not exist. An [IllegalStateException], so callers that only fail need not catch it.
 */
class StorageClassRefusedException(
    val storageClass: String,
    val reclaimPolicy: String?,
    message: String,
) : IllegalStateException(message)

/**
 * Runs the kit-data Retain check on [storageClass] and reports a refusal on [eventBus] as
 * `Setup.KitStorageClassRefused` before rethrowing it, so every path that checks the kit class
 * (`up`, the `kit-storage` step, `platform create-pvs`) tells subscribers the same typed fact.
 */
fun StorageClassRetainCheck.requireKitClass(
    storageClass: String,
    eventBus: EventBus,
) {
    try {
        require(storageClass, RetainedData.KitData)
    } catch (refused: StorageClassRefusedException) {
        eventBus.emit(
            Event.Setup.KitStorageClassRefused(
                storageClass = refused.storageClass,
                reclaimPolicy = refused.reclaimPolicy,
                reason = refused.message.orEmpty(),
            ),
        )
        throw refused
    }
}
