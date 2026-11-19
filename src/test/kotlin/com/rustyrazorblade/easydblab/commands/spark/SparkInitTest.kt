package com.rustyrazorblade.easydblab.commands.spark

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.EMRClusterState
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.services.EMRProvisioningService
import com.rustyrazorblade.easydblab.services.EmrClusterProvisioningConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for [SparkInit]: the EMR cluster it asks for carries the marker tags `down` and the IAM
 * conditions match on, plus this cluster's id.
 */
class SparkInitTest : BaseKoinTest() {
    private lateinit var mockEmrProvisioningService: EMRProvisioningService
    private lateinit var mockClusterStateManager: ClusterStateManager

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<EMRProvisioningService>().also { mockEmrProvisioningService = it } }
                single { mock<ClusterStateManager>().also { mockClusterStateManager = it } }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockEmrProvisioningService = getKoin().get()
        mockClusterStateManager = getKoin().get()
    }

    @Test
    fun `the EMR cluster carries both marker tags and the cluster id`() {
        val state =
            ClusterState(
                name = "test-cluster",
                infrastructure =
                    InfrastructureState(
                        vpcId = "vpc-123",
                        region = "us-west-2",
                        subnetIds = listOf("subnet-123"),
                        securityGroupId = "sg-123",
                    ),
                initConfig = InitConfig(name = "test-cluster"),
            )
        whenever(mockClusterStateManager.load()).thenReturn(state)
        whenever(mockEmrProvisioningService.provisionEmrCluster(any()))
            .thenReturn(EMRClusterState(clusterId = "j-123", clusterName = "test-cluster"))

        SparkInit().execute()

        val config = argumentCaptor<EmrClusterProvisioningConfig>()
        verify(mockEmrProvisioningService).provisionEmrCluster(config.capture())
        assertThat(config.firstValue.tags)
            .containsEntry(Constants.Vpc.LEGACY_TAG_KEY, Constants.Vpc.TAG_VALUE)
            .containsEntry(Constants.Vpc.TAG_KEY, Constants.Vpc.TAG_VALUE)
            .containsEntry(Constants.Vpc.CLUSTER_ID_TAG_KEY, state.clusterId)
    }
}
