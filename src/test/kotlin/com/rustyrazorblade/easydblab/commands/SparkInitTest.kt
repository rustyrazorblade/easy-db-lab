package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.commands.spark.SparkInit
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.EMRClusterState
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.services.EMRProvisioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

class SparkInitTest : BaseKoinTest() {
    private lateinit var mockEmrProvisioningService: EMRProvisioningService
    private lateinit var testClusterStateManager: ClusterStateManager

    override fun additionalTestModules(): List<Module> {
        val stateFile = File(tempDir, "state.json")
        testClusterStateManager = ClusterStateManager(stateFile)

        return listOf(
            module {
                single {
                    mock<EMRProvisioningService>().also {
                        mockEmrProvisioningService = it
                    }
                }
                single { testClusterStateManager }
            },
        )
    }

    private fun saveState(state: ClusterState) {
        testClusterStateManager.save(state)
    }

    @Test
    fun `should fail when infrastructure is not provisioned`() {
        saveState(
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                initConfig = InitConfig(name = "test"),
                infrastructure = null,
            ),
        )

        // Initialize mocks
        get<EMRProvisioningService>()

        val cmd = SparkInit()

        assertThatThrownBy { cmd.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Infrastructure not provisioned")
    }

    @Test
    fun `should fail when initConfig is missing`() {
        saveState(
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                initConfig = null,
                infrastructure =
                    InfrastructureState(
                        vpcId = "vpc-123",
                        region = "us-west-2",
                        subnetIds = listOf("subnet-123"),
                        securityGroupId = "sg-456",
                    ),
            ),
        )

        get<EMRProvisioningService>()

        val cmd = SparkInit()

        assertThatThrownBy { cmd.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Init configuration not found")
    }

    @Test
    fun `should fail when EMR cluster already exists`() {
        saveState(
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                initConfig = InitConfig(name = "test"),
                infrastructure =
                    InfrastructureState(
                        vpcId = "vpc-123",
                        region = "us-west-2",
                        subnetIds = listOf("subnet-123"),
                        securityGroupId = "sg-456",
                    ),
                emrCluster =
                    EMRClusterState(
                        clusterId = "j-EXISTING",
                        clusterName = "test-spark",
                        state = "WAITING",
                    ),
                s3Bucket = "easy-db-lab-test",
            ),
        )

        get<EMRProvisioningService>()

        val cmd = SparkInit()

        assertThatThrownBy { cmd.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("EMR cluster already exists")
    }

    @Test
    fun `should provision EMR and update state correctly`() {
        saveState(
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                initConfig = InitConfig(name = "test"),
                infrastructure =
                    InfrastructureState(
                        vpcId = "vpc-123",
                        region = "us-west-2",
                        subnetIds = listOf("subnet-123"),
                        securityGroupId = "sg-456",
                    ),
                s3Bucket = "easy-db-lab-test",
            ),
        )

        val expectedEmr =
            EMRClusterState(
                clusterId = "j-NEW123",
                clusterName = "test-spark",
                masterPublicDns = "master.dns.amazonaws.com",
                state = "WAITING",
            )

        get<EMRProvisioningService>()

        whenever(
            mockEmrProvisioningService.provisionEmrCluster(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            ),
        ).thenReturn(expectedEmr)

        val cmd = SparkInit()
        cmd.masterInstanceType = "m5.2xlarge"
        cmd.workerInstanceType = "m5.4xlarge"
        cmd.workerCount = 5
        cmd.execute()

        // Verify state was saved with EMR cluster info
        val savedState = testClusterStateManager.load()
        assertThat(savedState.emrCluster).isNotNull
        assertThat(savedState.emrCluster?.clusterId).isEqualTo("j-NEW123")
        assertThat(savedState.emrCluster?.masterPublicDns).isEqualTo("master.dns.amazonaws.com")

        // Verify initConfig was updated with spark settings
        assertThat(savedState.initConfig?.sparkEnabled).isTrue()
        assertThat(savedState.initConfig?.sparkMasterInstanceType).isEqualTo("m5.2xlarge")
        assertThat(savedState.initConfig?.sparkWorkerInstanceType).isEqualTo("m5.4xlarge")
        assertThat(savedState.initConfig?.sparkWorkerCount).isEqualTo(5)
    }

    @Test
    fun `should call EMRProvisioningService with correct parameters`() {
        saveState(
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                initConfig = InitConfig(name = "myenv"),
                infrastructure =
                    InfrastructureState(
                        vpcId = "vpc-123",
                        region = "us-west-2",
                        subnetIds = listOf("subnet-abc"),
                        securityGroupId = "sg-xyz",
                    ),
                s3Bucket = "easy-db-lab-test",
                clusterId = "cluster-id-123",
            ),
        )

        val expectedEmr =
            EMRClusterState(
                clusterId = "j-RESULT",
                clusterName = "myenv-spark",
                state = "WAITING",
            )

        get<EMRProvisioningService>()

        whenever(
            mockEmrProvisioningService.provisionEmrCluster(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            ),
        ).thenReturn(expectedEmr)

        val cmd = SparkInit()
        cmd.masterInstanceType = "m5.xlarge"
        cmd.workerInstanceType = "m5.2xlarge"
        cmd.workerCount = 4
        cmd.execute()

        verify(mockEmrProvisioningService).provisionEmrCluster(
            clusterName = eq("myenv"),
            masterInstanceType = eq("m5.xlarge"),
            workerInstanceType = eq("m5.2xlarge"),
            workerCount = eq(4),
            subnetId = eq("subnet-abc"),
            securityGroupId = eq("sg-xyz"),
            keyName = eq("test-key"),
            clusterState = any(),
            tags = any(),
        )
    }

    @Test
    fun `should use default instance types when not specified`() {
        val command = SparkInit()

        assertThat(command.masterInstanceType).isEqualTo("m5.xlarge")
        assertThat(command.workerInstanceType).isEqualTo("m5.xlarge")
        assertThat(command.workerCount).isEqualTo(3)
    }
}
