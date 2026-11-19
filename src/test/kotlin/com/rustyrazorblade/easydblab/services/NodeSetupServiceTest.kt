package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * [NodeSetupService]'s app-node setup reads the region from the node it sets up, not from another
 * node, so a node's setup depends on no other node (design D2), and every node gets the packaged OS
 * tuning files beside `setup_instance.sh` before the script runs. The remote calls are mocked at
 * the SSH boundary; the uploaded `stress.env` is read as it is uploaded.
 */
class NodeSetupServiceTest {
    private val remoteOps: RemoteOperationsService = mock()
    private val service = NodeSetupService(remoteOps, mock(), NodeTuningFiles())
    private val context = NodeSetupContext("10.0.1.100", "10.0.1.5", "test-abc", "default")
    private val uploads = mutableMapOf<String, MutableList<String>>()

    private fun host(
        alias: String,
        ip: String,
    ) = Host(public = "54.0.0.$ip", private = "10.0.2.$ip", alias = alias, availabilityZone = "us-west-2a")

    @BeforeEach
    fun setup() {
        whenever(remoteOps.executeRemotely(any(), any(), any(), any())).thenReturn(Response(""))
        whenever(remoteOps.upload(any(), any(), any(), any())).thenAnswer {
            val target = it.getArgument<Host>(0).alias
            val source = it.getArgument<Path>(1)
            if (Files.exists(source)) uploads.getOrPut(target) { mutableListOf() }.add(Files.readString(source))
            Unit
        }
    }

    private fun regionOn(
        alias: String,
        region: String,
    ) {
        whenever(remoteOps.executeRemotely(argThat { this.alias == alias }, eq(ImdsRegion.COMMAND), any(), any()))
            .thenReturn(Response(region))
    }

    @Test
    fun `each app node's stress env names the database host and the region that node reports`() {
        regionOn("app0", "us-west-2\n")
        regionOn("app1", "eu-central-1\n")

        service.setupApp(host("app0", "10"), context)
        service.setupApp(host("app1", "11"), context)

        val stressEnv = { alias: String -> uploads.getValue(alias).single { it.contains("CASSANDRA_EASY_STRESS") } }
        assertThat(stressEnv("app0"))
            .contains("CASSANDRA_EASY_STRESS_CASSANDRA_HOST=10.0.1.100")
            .contains("CASSANDRA_EASY_STRESS_DEFAULT_DC=us-west-2")
        assertThat(stressEnv("app1")).contains("CASSANDRA_EASY_STRESS_DEFAULT_DC=eu-central-1")
    }

    @Test
    fun `the tuning files replace any earlier copy beside the setup script before it runs`() {
        val uploadedFiles = mutableListOf<String>()
        whenever(remoteOps.uploadDirectory(any(), any<File>(), any())).thenAnswer {
            uploadedFiles += it.getArgument<File>(1).list()!!.sorted()
            Unit
        }
        val db0 = host("db0", "10")

        service.setupControl(db0, context)

        val order = inOrder(remoteOps)
        order.verify(remoteOps).executeRemotely(eq(db0), eq("rm -rf node-tuning"), any(), any())
        order.verify(remoteOps).uploadDirectory(eq(db0), any<File>(), eq("node-tuning"))
        order.verify(remoteOps).upload(eq(db0), any(), eq("setup_instance.sh"), any())
        order.verify(remoteOps).executeRemotely(eq(db0), eq("sudo bash setup_instance.sh"), any(), any())
        assertThat(uploadedFiles).containsExactlyElementsOf(Constants.NodeTuning.FILES.sorted())
    }

    @Test
    fun `each node's setup is given the instance type its server type has in the cluster state`() {
        val state =
            ClusterState(
                name = "test",
                hosts =
                    mapOf(
                        ServerType.Control to
                            listOf(ClusterHost("54.0.0.12", "10.0.2.12", "control0", "us-west-2a")),
                    ),
                initConfig =
                    InitConfig(instanceType = "i4i.2xlarge", stressInstanceType = "c6id.4xlarge", controlInstanceType = "m5d.xlarge"),
            )
        val fromState = NodeSetupContext.from(state)
        regionOn("app0", "us-west-2\n")

        service.setup(ServerType.Cassandra, host("db0", "10"), fromState)
        service.setup(ServerType.Stress, host("app0", "11"), fromState)
        service.setup(ServerType.Control, host("control0", "12"), fromState)

        val setupCommands = argumentCaptor<String>()
        verify(remoteOps, atLeastOnce()).executeRemotely(any(), setupCommands.capture(), any(), any())
        assertThat(setupCommands.allValues.filter { it.endsWith("setup_instance.sh") }).containsExactly(
            "sudo EDL_INSTANCE_TYPE=i4i.2xlarge bash setup_instance.sh",
            "sudo EDL_INSTANCE_TYPE=c6id.4xlarge bash setup_instance.sh",
            "sudo EDL_INSTANCE_TYPE=m5d.xlarge bash setup_instance.sh",
        )
    }

    @Test
    fun `a state with no init config runs setup with no instance type, and a type is shell quoted`() {
        assertThat(NodeSetupService.setupCommand(null)).isEqualTo("sudo bash setup_instance.sh")
        assertThat(NodeSetupService.setupCommand("odd type")).isEqualTo("sudo EDL_INSTANCE_TYPE='odd type' bash setup_instance.sh")
    }
}
