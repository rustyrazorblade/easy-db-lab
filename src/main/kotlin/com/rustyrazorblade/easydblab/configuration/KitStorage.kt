package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract
import com.rustyrazorblade.easydblab.configuration.environment.KitStorageDefault
import io.fabric8.kubernetes.api.model.Quantity

/**
 * Where a kit's claims get their volumes, as a value the environment resolves once from cluster
 * state. Kit templates name `__KIT_STORAGE_CLASS__` and `__STORAGE_SIZE__` instead of a fixed class
 * or a selector, so one template binds on the local volumes of nodes easy-db-lab owns and on any
 * dynamic StorageClass that retains its volumes: the recorded one, or elsewhere the cluster's default.
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

    /**
     * No class recorded and the cluster has no default class, off the nodes easy-db-lab owns. Kit
     * claims name no class; the `kit-storage` step refuses it (`Setup.NoDefaultKitStorageClass`),
     * since nothing would provision or retain the kit's volumes.
     */
    data class NoStorageClass(
        override val defaultSize: Quantity,
    ) : KitStorage {
        override fun storageClassFor(kit: String): String = ""
    }

    companion object {
        /**
         * The kit storage of [state]: a recorded kit StorageClass is [Provisioned]. With none, the
         * contract's [KitStorageDefault] decides: [LocalVolumes] on the nodes easy-db-lab owns, else
         * the cluster's default class from [defaultClass] ([NoStorageClass] when it has none).
         * [defaultClass] is only called in that last case. A recorded kit storage size replaces
         * every default.
         */
        fun from(
            state: ClusterState,
            defaultClass: () -> String?,
        ): KitStorage {
            val size = defaultSize(state)
            val recorded = state.initConfig?.kitStorageClass
            return when {
                recorded != null -> Provisioned(recorded, size)
                else ->
                    when (ClusterContract.of(state).kitStorageDefault) {
                        KitStorageDefault.LocalVolumes -> LocalVolumes(size)
                        KitStorageDefault.ClusterDefaultClass ->
                            defaultClass()?.let { Provisioned(it, size) } ?: NoStorageClass(size)
                    }
            }
        }

        /**
         * The size a kit claim requests by default, which needs no class: the recorded kit storage size,
         * else 10Ti on local volumes and [Constants.K8s.DEFAULT_PROVISIONED_KIT_STORAGE_SIZE] on a class.
         */
        fun defaultSize(state: ClusterState): Quantity {
            val config = state.initConfig
            config?.kitStorageSize?.let { return Quantity(it) }
            val onLocalVolumes =
                config?.kitStorageClass == null &&
                    ClusterContract.of(state).kitStorageDefault == KitStorageDefault.LocalVolumes
            return Quantity(
                if (onLocalVolumes) Constants.K8s.LOCAL_KIT_STORAGE_SIZE else Constants.K8s.DEFAULT_PROVISIONED_KIT_STORAGE_SIZE,
            )
        }
    }
}
