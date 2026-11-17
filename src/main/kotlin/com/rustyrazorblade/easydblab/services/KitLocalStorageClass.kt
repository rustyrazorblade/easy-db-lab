package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.storage.StorageClass
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder

/**
 * The shape of a kit's own local StorageClass `edl-local-<kit>`: no provisioner, so its claims
 * bind only the PVs created for the kit; `WaitForFirstConsumer`, so a claim binds the PV on the
 * node the scheduler picked; and `Retain`, so a deleted claim never deletes the kit's data.
 *
 * A class of that name already in the cluster is reused only when it has that shape. A
 * StorageClass cannot be changed in place, and a leftover one with `Delete` or another
 * provisioner would silently break the guarantees above, so the mismatch fails the kit's start.
 */
object KitLocalStorageClass {
    private const val PROVISIONER = "kubernetes.io/no-provisioner"
    private const val BINDING_MODE = "WaitForFirstConsumer"
    private const val RECLAIM_POLICY = "Retain"

    /** What Kubernetes binds with when a StorageClass sets no `volumeBindingMode`. */
    private const val DEFAULT_BINDING_MODE = "Immediate"

    /** What Kubernetes reclaims with when a StorageClass sets no `reclaimPolicy`. */
    private const val DEFAULT_RECLAIM_POLICY = "Delete"

    /** The class [name], labelled as easy-db-lab's and cluster [clusterId]'s. */
    fun build(
        name: String,
        clusterId: String,
    ): StorageClass =
        StorageClassBuilder()
            .withNewMetadata()
            .withName(name)
            .addToLabels(Constants.K8s.MANAGED_BY_LABEL, Constants.K8s.MANAGED_BY_VALUE)
            .addToLabels(Constants.K8s.CLUSTER_LABEL, clusterId)
            .endMetadata()
            .withProvisioner(PROVISIONER)
            .withVolumeBindingMode(BINDING_MODE)
            .withReclaimPolicy(RECLAIM_POLICY)
            .build()

    /**
     * Fails, naming every field that differs, unless [existing] is a class [build] would make.
     *
     * @throws IllegalStateException on a provisioner, binding mode or reclaim policy mismatch
     */
    fun requireReusable(existing: StorageClass) {
        val actual =
            listOf(
                Triple("provisioner", existing.provisioner, PROVISIONER),
                Triple("volumeBindingMode", existing.volumeBindingMode ?: DEFAULT_BINDING_MODE, BINDING_MODE),
                Triple("reclaimPolicy", existing.reclaimPolicy ?: DEFAULT_RECLAIM_POLICY, RECLAIM_POLICY),
            )
        val mismatches = actual.filter { (_, value, expected) -> value != expected }
        check(mismatches.isEmpty()) {
            val name = existing.metadata.name
            val fields = mismatches.joinToString(", ") { (field, value, expected) -> "$field $value (expected $expected)" }
            "StorageClass $name already exists but is not a kit local class: $fields. " +
                "Delete it (kubectl delete storageclass $name) so the kit's start recreates it; " +
                "its PVs and their data are kept."
        }
    }
}
