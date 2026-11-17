package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.Quantity

/**
 * Where a kit's claims get their volumes, as a value the environment resolves once from cluster
 * state. Kit templates name `__KIT_STORAGE_CLASS__` and `__STORAGE_SIZE__` instead of a fixed class
 * or a selector, so one template binds on the local volumes of nodes easy-db-lab owns and on any
 * dynamic StorageClass that retains its volumes.
 */
sealed interface KitStorage {
    /** The size a kit claim requests when neither the kit's `--storage-size` nor anything else says. */
    val defaultSize: Quantity

    /** The StorageClass [kit]'s claims name. */
    fun storageClassFor(kit: String): String

    /**
     * Local PVs on `/mnt/db1`, created per kit by the `kit-storage` step on the kit's own
     * `edl-local-<kit>` class, so one kit's claims can only bind that kit's PVs.
     */
    data class LocalVolumes(
        override val defaultSize: Quantity,
    ) : KitStorage {
        override fun storageClassFor(kit: String): String = "${Constants.K8s.KIT_LOCAL_CLASS_PREFIX}$kit"
    }

    /**
     * A dynamic StorageClass the cluster provisions from; it must reclaim with `Retain`.
     *
     * @property storageClass the class every kit claim names.
     */
    data class Provisioned(
        val storageClass: String,
        override val defaultSize: Quantity,
    ) : KitStorage {
        override fun storageClassFor(kit: String): String = storageClass
    }

    companion object {
        /**
         * The kit storage [state] recorded at `init`: a recorded kit StorageClass is [Provisioned],
         * none is [LocalVolumes]. A recorded kit storage size replaces either default.
         */
        fun from(state: ClusterState): KitStorage {
            val config = state.initConfig
            val size = config?.kitStorageSize?.let(::Quantity)
            return when (val storageClass = config?.kitStorageClass) {
                null -> LocalVolumes(size ?: Quantity(Constants.K8s.LOCAL_KIT_STORAGE_SIZE))
                else -> Provisioned(storageClass, size ?: Quantity(Constants.K8s.DEFAULT_PROVISIONED_KIT_STORAGE_SIZE))
            }
        }
    }
}
