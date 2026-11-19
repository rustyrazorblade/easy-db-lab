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

    /**
     * An account bucket made before the new key existed carries only the legacy marker, so `up`
     * runs the bucket backfill on it (the merge itself is covered by `AwsS3BucketServiceTest` and
     * its LocalStack test).
     */
    @Test
    fun `up backfills the marker tags on the account bucket it uses`() {
        val state = happyState()
        whenever(mockClusterStateManager.load()).thenReturn(state)

        Up(tailnetRetryInterval = Duration.ZERO, timing = BringUpHarness.FAST).execute()

        assertThat(state.s3Bucket).isEqualTo("easy-db-lab-test-bucket")
        verify(mockS3BucketService).ensureMarkerTags("easy-db-lab-test-bucket")
    }

    /** A user `--tag` that names a marker or the cluster id must not override them, or teardown would lose the cluster. */
    @Test
    fun `user tags cannot override the marker tags or the cluster id`() {
        val base = happyState()
        val state =
            base.copy(
                initConfig =
                    base.initConfig!!.copy(
                        tags =
                            mapOf(
                                Constants.Vpc.LEGACY_TAG_KEY to "0",
                                Constants.Vpc.TAG_KEY to "x",
                                Constants.Vpc.CLUSTER_ID_TAG_KEY to "evil",
                                "team" to "lab",
                            ),
                    ),
            )
        whenever(mockClusterStateManager.load()).thenReturn(state)

        Up(tailnetRetryInterval = Duration.ZERO, timing = BringUpHarness.FAST).execute()

        val vpcTags = argumentCaptor<Map<String, String>>()
        verify(mockVpcService).createVpc(any(), any(), vpcTags.capture())
        val instances = argumentCaptor<InstanceProvisioningConfig>()
        verify(mockClusterProvisioningService).provisionAll(instances.capture(), any(), any(), any())

        for (tags in listOf(vpcTags.firstValue, instances.firstValue.tags)) {
            assertThat(tags)
                .containsAllEntriesOf(bothMarkers)
                .containsEntry(Constants.Vpc.CLUSTER_ID_TAG_KEY, state.clusterId)
                .containsEntry("team", "lab")
        }
    }
}
