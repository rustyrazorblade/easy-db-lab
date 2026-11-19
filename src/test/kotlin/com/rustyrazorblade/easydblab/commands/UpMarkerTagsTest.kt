package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.InstanceProvisioningConfig
import com.rustyrazorblade.easydblab.services.OptionalServicesConfig
import com.rustyrazorblade.easydblab.services.bringup.BringUpHarness
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * Phase 1 of the tag rename: `up` writes the new `easy_db_lab` marker alongside the legacy
 * `easy_cass_lab` one on everything it creates, so resources made now are found by both the
 * readers of today (legacy key) and those of phase 2 (new key).
 */
class UpMarkerTagsTest : UpTestFixture() {
    private val bothMarkers =
        mapOf(
            Constants.Vpc.LEGACY_TAG_KEY to Constants.Vpc.TAG_VALUE,
            Constants.Vpc.TAG_KEY to Constants.Vpc.TAG_VALUE,
        )

    @Test
    fun `the VPC, the instances and the optional services carry both marker tags and the cluster id`() {
        val state = happyState()
        whenever(mockClusterStateManager.load()).thenReturn(state)

        Up(tailnetRetryInterval = Duration.ZERO, timing = BringUpHarness.FAST).execute()

        val vpcTags = argumentCaptor<Map<String, String>>()
        verify(mockVpcService).createVpc(any(), any(), vpcTags.capture())
        val instances = argumentCaptor<InstanceProvisioningConfig>()
        val services = argumentCaptor<OptionalServicesConfig>()
        verify(mockClusterProvisioningService).provisionAll(instances.capture(), services.capture(), any(), any())

        assertThat(vpcTags.firstValue).containsAllEntriesOf(bothMarkers).containsEntry(Constants.Vpc.CLUSTER_ID_TAG_KEY, state.clusterId)
        assertThat(
            instances.firstValue.tags,
        ).containsAllEntriesOf(bothMarkers).containsEntry(Constants.Vpc.CLUSTER_ID_TAG_KEY, state.clusterId)
        assertThat(
            services.firstValue.tags,
        ).containsAllEntriesOf(bothMarkers).containsEntry(Constants.Vpc.CLUSTER_ID_TAG_KEY, state.clusterId)
    }
}
