package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InfrastructureStatus
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.services.aws.CompactorService
import com.rustyrazorblade.easydblab.services.aws.EMRService
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import java.io.File

/**
 * `down` records the teardown as soon as the VPC is gone, and a re-run of a `down` that failed after
 * that point finishes the remaining steps instead of failing on the missing VPC.
 *
 * The cluster state is real and written to disk; the AWS calls that discover and delete the VPC
 * (EC2, EMR, OpenSearch) and the account compactor are the only fakes.
 */
class DownResumeTest : BaseKoinTest() {
    private val clusterVpcId = "vpc-test123"
    private val bucket = "test-bucket"
    private val vpcService: VpcService = mock()
    private val emrService: EMRService = mock()
    private val openSearchService: OpenSearchService = mock()
    private lateinit var stateFile: File

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { ClusterStateManager(stateFile) }
                single { AwsInfrastructureService(vpcService, emrService, openSearchService, get<EventBus>()) }
            },
        )

    private val compactor get() = getKoin().get<CompactorService>()

    private fun savedState(): ClusterState = ClusterStateManager(stateFile).load()

    private fun errorOutput(): String = (getKoin().get<OutputHandler>() as BufferedOutputHandler).errors.joinToString("\n") { it.first }

    @BeforeEach
    fun writeRunningCluster() {
        stateFile = File(tempDir, "state.json")
        ClusterStateManager(stateFile).save(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = bucket,
                initConfig = InitConfig(region = "us-west-2"),
            ).apply {
                updateInfrastructure(
                    InfrastructureState(
                        vpcId = clusterVpcId,
                        region = "us-west-2",
                        subnetIds = listOf("subnet-test"),
                        securityGroupId = "sg-test",
                    ),
                )
                markInfrastructureUp()
            },
        )
        whenever(vpcService.findInstancesInVpc(any())).thenReturn(emptyList())
        whenever(vpcService.findSubnetsInVpc(any())).thenReturn(listOf("subnet-test"))
        whenever(vpcService.findSecurityGroupsInVpc(any())).thenReturn(emptyList())
        whenever(vpcService.findNatGatewaysInVpc(any())).thenReturn(emptyList())
        whenever(vpcService.findInternetGatewayByVpc(any())).thenReturn(null)
        whenever(vpcService.findRouteTablesInVpc(any())).thenReturn(emptyList())
        whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
        whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())
    }

    private fun vpcExists() {
        whenever(vpcService.getVpcName(clusterVpcId)).thenReturn("test-cluster")
    }

    private fun vpcIsGone() {
        whenever(vpcService.getVpcName(clusterVpcId)).thenThrow(
            Ec2Exception
                .builder()
                .awsErrorDetails(
                    AwsErrorDetails
                        .builder()
                        .errorCode(Constants.AWS.EC2_VPC_NOT_FOUND)
                        .errorMessage("The vpc ID '$clusterVpcId' does not exist")
                        .build(),
                ).statusCode(400)
                .build(),
        )
    }

    private fun down(dryRun: Boolean = false): Int =
        Down()
            .apply {
                autoApprove = true
                this.dryRun = dryRun
            }.call()

    @Test
    fun `a re-run whose VPC an earlier down already deleted finishes the teardown`() {
        vpcIsGone()

        val exitCode = down()

        assertThat(exitCode).isZero()
        assertThat(savedState().infrastructureStatus).isEqualTo(InfrastructureStatus.DOWN)
        assertThat(savedState().vpcId).isNull()
        verify(compactor).stopIfLastCluster(bucket, emptySet())
        verify(vpcService, never()).deleteVpc(any())
    }

    @Test
    fun `the cluster is recorded as down before the compactor step runs`() {
        vpcExists()
        var stateAtCompactorStep: ClusterState? = null
        doAnswer { stateAtCompactorStep = savedState() }.whenever(compactor).stopIfLastCluster(any(), any())

        assertThat(down()).isZero()

        verify(vpcService).deleteVpc(clusterVpcId)
        assertThat(stateAtCompactorStep).isNotNull()
        assertThat(stateAtCompactorStep?.infrastructureStatus).isEqualTo(InfrastructureStatus.DOWN)
        assertThat(stateAtCompactorStep?.vpcId).isNull()
    }

    @Test
    fun `a compactor step that fails after the VPC is deleted leaves the cluster down, and a re-run completes`() {
        vpcExists()
        doThrow(IllegalStateException("census failed in ap-south-1"))
            .doAnswer { }
            .whenever(compactor)
            .stopIfLastCluster(any(), any())

        assertThat(down()).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(errorOutput()).contains("account compactor step failed", "ap-south-1", "down' again")
        assertThat(savedState().infrastructureStatus).isEqualTo(InfrastructureStatus.DOWN)
        assertThat(savedState().vpcId).isNull()

        assertThat(down()).isZero()

        verify(vpcService, times(1)).deleteVpc(clusterVpcId)
        verify(compactor, times(2)).stopIfLastCluster(eq(bucket), any())
    }

    @Test
    fun `a dry run leaves the cluster state as it is`() {
        vpcExists()

        assertThat(down(dryRun = true)).isZero()

        assertThat(savedState().infrastructureStatus).isEqualTo(InfrastructureStatus.UP)
        assertThat(savedState().vpcId).isEqualTo(clusterVpcId)
        verify(vpcService, never()).deleteVpc(any())
        verify(compactor, never()).stopIfLastCluster(any(), any())
    }
}
