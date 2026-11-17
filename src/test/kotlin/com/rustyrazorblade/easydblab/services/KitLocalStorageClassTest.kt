package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.storage.StorageClass
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A kit's local class already in the cluster is reused only when it is the class easy-db-lab would
 * create: a class left with another provisioner, binding mode or reclaim policy would bind the
 * kit's claims elsewhere or delete its data, and a StorageClass cannot be changed in place.
 */
class KitLocalStorageClassTest {
    private fun storageClass(
        provisioner: String = "kubernetes.io/no-provisioner",
        bindingMode: String? = "WaitForFirstConsumer",
        reclaimPolicy: String? = "Retain",
    ): StorageClass =
        StorageClassBuilder()
            .withNewMetadata()
            .withName("edl-local-kafka")
            .endMetadata()
            .withProvisioner(provisioner)
            .withVolumeBindingMode(bindingMode)
            .withReclaimPolicy(reclaimPolicy)
            .build()

    @Test
    fun `the class easy-db-lab creates is accepted`() {
        assertThatCode { KitLocalStorageClass.requireReusable(storageClass()) }.doesNotThrowAnyException()
    }

    @Test
    fun `a class that deletes is refused, naming the class, the field and the fix`() {
        assertThatThrownBy { KitLocalStorageClass.requireReusable(storageClass(reclaimPolicy = "Delete")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("StorageClass edl-local-kafka")
            .hasMessageContaining("reclaimPolicy Delete (expected Retain)")
            .hasMessageContaining("kubectl delete storageclass edl-local-kafka")
    }

    @Test
    fun `a class with no policy or binding mode reads as the Kubernetes defaults and is refused`() {
        assertThatThrownBy { KitLocalStorageClass.requireReusable(storageClass(bindingMode = null, reclaimPolicy = null)) }
            .hasMessageContaining("volumeBindingMode Immediate (expected WaitForFirstConsumer)")
            .hasMessageContaining("reclaimPolicy Delete (expected Retain)")
    }

    @Test
    fun `a class with another provisioner is refused`() {
        assertThatThrownBy { KitLocalStorageClass.requireReusable(storageClass(provisioner = "rancher.io/local-path")) }
            .hasMessageContaining("provisioner rancher.io/local-path (expected kubernetes.io/no-provisioner)")
    }

    @Test
    fun `the class it builds is one it accepts`() {
        val built = KitLocalStorageClass.build("edl-local-kafka", "cid-1")

        assertThatCode { KitLocalStorageClass.requireReusable(built) }.doesNotThrowAnyException()
        assertThat(built.metadata.labels).containsEntry("easydblab/cluster", "cid-1")
    }
}
