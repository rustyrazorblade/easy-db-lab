package com.rustyrazorblade.easydblab.commands.platform

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.KitStorage
import io.fabric8.kubernetes.api.model.PersistentVolume
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder
import io.fabric8.kubernetes.api.model.Quantity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for PlatformInfo display formatting.
 * Calls buildInfoText() directly — PlatformInfo is a read-only display command
 * that uses println() with no associated events.
 */
class PlatformInfoTest {
    private val local = KitStorage.LocalVolumes(Quantity("10Ti"))

    @Test
    fun `on local volumes it names the per-kit class pattern and the default size`() {
        val output = PlatformInfo.buildInfoText(dbNodeCount = 3, appNodeCount = 2, kitStorage = local)
        assertThat(output).contains("Kit storage: local volumes, StorageClass edl-local-<kit>, default size 10Ti")
    }

    @Test
    fun `on a provisioned class it names the class and the default size`() {
        val output =
            PlatformInfo.buildInfoText(
                dbNodeCount = 3,
                appNodeCount = 2,
                kitStorage = KitStorage.Provisioned("retain-x", Quantity("100Gi")),
            )
        assertThat(output).contains("Kit storage: StorageClass retain-x, default size 100Gi")
    }

    @Test
    fun `outputs correct db and app node counts`() {
        val output = PlatformInfo.buildInfoText(dbNodeCount = 3, appNodeCount = 2, kitStorage = local)
        assertThat(output).contains("DB nodes: 3")
        assertThat(output).contains("App nodes: 2")
    }

    @Test
    fun `outputs node ordinal label key`() {
        val output = PlatformInfo.buildInfoText(dbNodeCount = 1, appNodeCount = 0, kitStorage = local)
        assertThat(output).contains(Constants.NODE_ORDINAL_LABEL)
    }

    @Test
    fun `counts only Available PVs, per node pool of their affinity`() {
        val pvs =
            listOf(
                pv("data-kafka-0", pool = "db", phase = "Available"),
                pv("data-kafka-1", pool = "db", phase = "Available"),
                pv("data-kafka-2", pool = "db", phase = "Bound"),
                pv("data-presto-0", pool = "app", phase = "Available"),
                pv("data-presto-1", pool = "app", phase = "Released"),
            )

        assertThat(PlatformInfo.availablePvsByPool(pvs)).isEqualTo(mapOf("db" to 2, "app" to 1))
    }

    @Test
    fun `on local volumes it shows the available PVs of each node pool, zero for a pool with none`() {
        val output =
            PlatformInfo.buildInfoText(
                dbNodeCount = 3,
                appNodeCount = 2,
                kitStorage = local,
                availablePvs = PvAvailability.Counted(mapOf("db" to 2)),
            )
        assertThat(output).contains("DB nodes: 3  (nodeSelector: type=db, available PVs: 2)")
        assertThat(output).contains("App nodes: 2  (nodeSelector: type=app, available PVs: 0)")
    }

    @Test
    fun `on local volumes it says why the PV counts could not be read`() {
        val output =
            PlatformInfo.buildInfoText(
                dbNodeCount = 3,
                appNodeCount = 2,
                kitStorage = local,
                availablePvs = PvAvailability.Unreadable("connection refused"),
            )
        assertThat(output).contains("Available PVs: could not be read (connection refused)")
    }

    @Test
    fun `on a provisioned class it shows no PV counts`() {
        val output =
            PlatformInfo.buildInfoText(
                dbNodeCount = 3,
                appNodeCount = 2,
                kitStorage = KitStorage.Provisioned("retain-x", Quantity("100Gi")),
            )
        assertThat(output).doesNotContain("available PVs").doesNotContain("Available PVs")
    }

    private fun pv(
        name: String,
        pool: String,
        phase: String,
    ): PersistentVolume =
        PersistentVolumeBuilder()
            .withNewMetadata()
            .withName(name)
            .endMetadata()
            .withNewSpec()
            .withNewNodeAffinity()
            .withNewRequired()
            .addNewNodeSelectorTerm()
            .addNewMatchExpression()
            .withKey(Constants.NODE_TYPE_LABEL)
            .withOperator("In")
            .withValues(pool)
            .endMatchExpression()
            .addNewMatchExpression()
            .withKey(Constants.NODE_ORDINAL_LABEL)
            .withOperator("In")
            .withValues("0")
            .endMatchExpression()
            .endNodeSelectorTerm()
            .endRequired()
            .endNodeAffinity()
            .endSpec()
            .withNewStatus()
            .withPhase(phase)
            .endStatus()
            .build()

    @Test
    fun `reports zero app nodes when no stress hosts configured`() {
        val output = PlatformInfo.buildInfoText(dbNodeCount = 1, appNodeCount = 0, kitStorage = local)
        assertThat(output).contains("App nodes: 0")
        assertThat(output).contains("DB nodes: 1")
    }
}
