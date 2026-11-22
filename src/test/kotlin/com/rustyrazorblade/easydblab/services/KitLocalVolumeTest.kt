package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.tenancy.OwnershipLabels
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A kit's local PV is cluster-scoped, so the ownership labels are the only way a later sweep can
 * tell which cluster's data it holds; the kit label keeps `deleteLocalPersistentVolumes` working.
 */
class KitLocalVolumeTest {
    private val config =
        PersistentVolumeConfig(
            dbName = "kafka",
            localPath = "/mnt/db1/kafka/data",
            count = 3,
            storageSize = "10Ti",
            storageClass = "edl-local-kafka",
            namespace = "lab-a",
            clusterId = "id-1",
        )

    @Test
    fun `the PV carries the cluster's ownership labels and keeps its kit and component labels`() {
        val pv = KitLocalVolume.build(config, ordinal = 2)

        assertThat(pv.metadata.name).isEqualTo("data-kafka-2")
        assertThat(pv.metadata.labels)
            .containsAllEntriesOf(OwnershipLabels.of("id-1"))
            .containsEntry(Constants.PV_KIT_LABEL, "kafka")
            .containsEntry("app.kubernetes.io/component", "data")
    }

    @Test
    fun `the PV is pinned to its pool and ordinal`() {
        val pv = KitLocalVolume.build(config.copy(nodeType = "app"), ordinal = 1)

        val expressions =
            pv.spec.nodeAffinity.required.nodeSelectorTerms
                .single()
                .matchExpressions
        assertThat(expressions.associate { it.key to it.values })
            .isEqualTo(mapOf(Constants.NODE_TYPE_LABEL to listOf("app"), Constants.NODE_ORDINAL_LABEL to listOf("1")))
    }
}
