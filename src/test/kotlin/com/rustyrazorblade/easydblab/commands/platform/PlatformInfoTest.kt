package com.rustyrazorblade.easydblab.commands.platform

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.KitStorage
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
    fun `reports zero app nodes when no stress hosts configured`() {
        val output = PlatformInfo.buildInfoText(dbNodeCount = 1, appNodeCount = 0, kitStorage = local)
        assertThat(output).contains("App nodes: 0")
        assertThat(output).contains("DB nodes: 1")
    }
}
