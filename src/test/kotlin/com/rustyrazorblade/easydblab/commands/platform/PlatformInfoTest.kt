package com.rustyrazorblade.easydblab.commands.platform

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for PlatformInfo display formatting.
 * Calls buildInfoText() directly — PlatformInfo is a read-only display command
 * that uses println() with no associated events.
 */
class PlatformInfoTest {
    @Test
    fun `outputs both storage classes`() {
        val output = PlatformInfo.buildInfoText(dbNodeCount = 3, appNodeCount = 2)
        assertThat(output).contains(Constants.K8s.LOCAL_STORAGE_CLASS)
        assertThat(output).contains(Constants.K8s.LOCAL_STORAGE_WFC_CLASS)
    }

    @Test
    fun `outputs correct db and app node counts`() {
        val output = PlatformInfo.buildInfoText(dbNodeCount = 3, appNodeCount = 2)
        assertThat(output).contains("DB nodes: 3")
        assertThat(output).contains("App nodes: 2")
    }

    @Test
    fun `outputs node ordinal label key`() {
        val output = PlatformInfo.buildInfoText(dbNodeCount = 1, appNodeCount = 0)
        assertThat(output).contains(Constants.NODE_ORDINAL_LABEL)
    }

    @Test
    fun `reports zero app nodes when no stress hosts configured`() {
        val output = PlatformInfo.buildInfoText(dbNodeCount = 1, appNodeCount = 0)
        assertThat(output).contains("App nodes: 0")
        assertThat(output).contains("DB nodes: 1")
    }
}
