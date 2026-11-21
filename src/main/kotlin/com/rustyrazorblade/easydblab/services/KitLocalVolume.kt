package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.tenancy.OwnershipLabels
import io.fabric8.kubernetes.api.model.PersistentVolume
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder
import io.fabric8.kubernetes.api.model.Quantity

/**
 * The shape of one of a kit's local PVs: `Retain`, in the kit's own class, on one node's
 * directory. A PV is cluster-scoped, so it carries the cluster's ownership labels as well as the
 * kit label `deleteLocalPersistentVolumes` selects by.
 */
object KitLocalVolume {
    /** The PV for [ordinal] of [config]. */
    fun build(
        config: PersistentVolumeConfig,
        ordinal: Int,
    ): PersistentVolume =
        PersistentVolumeBuilder()
            .withNewMetadata()
            .withName("${config.volumeClaimTemplateName}-${config.dbName}-$ordinal")
            .addToLabels(Constants.PV_KIT_LABEL, config.dbName)
            .addToLabels("app.kubernetes.io/component", "data")
            .addToLabels(OwnershipLabels.of(config.clusterId))
            .endMetadata()
            .withNewSpec()
            .addToCapacity("storage", Quantity(config.storageSize))
            .withAccessModes(Constants.K8s.READ_WRITE_ONCE)
            .withPersistentVolumeReclaimPolicy("Retain")
            .withStorageClassName(config.storageClass)
            .withNewLocal()
            .withPath(config.localPath)
            .endLocal()
            // db and app nodes both carry ordinals, so the ordinal alone would let
            // a db PV bind on the app node with the same ordinal.
            .withNewNodeAffinity()
            .withNewRequired()
            .addNewNodeSelectorTerm()
            .addNewMatchExpression()
            .withKey(Constants.NODE_TYPE_LABEL)
            .withOperator("In")
            .withValues(config.nodeType)
            .endMatchExpression()
            .addNewMatchExpression()
            .withKey(Constants.NODE_ORDINAL_LABEL)
            .withOperator("In")
            .withValues(ordinal.toString())
            .endMatchExpression()
            .endNodeSelectorTerm()
            .endRequired()
            .endNodeAffinity()
            .endSpec()
            .build()
}
