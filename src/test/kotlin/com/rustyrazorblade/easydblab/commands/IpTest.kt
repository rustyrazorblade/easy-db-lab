package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class IpTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private val stdout = ByteArrayOutputStream()
    private val originalOut = System.out

    private val testHosts =
        mapOf(
            ServerType.Cassandra to
                listOf(
                    ClusterHost(
                        publicIp = "54.1.2.3",
                        privateIp = "10.0.1.100",
                        alias = "db0",
                        availabilityZone = "us-west-2a",
                    ),
                    ClusterHost(
                        publicIp = "54.1.2.4",
                        privateIp = "10.0.1.101",
                        alias = "db1",
                        availabilityZone = "us-west-2b",
                    ),
                ),
            ServerType.Stress to
                listOf(
                    ClusterHost(
                        publicIp = "54.2.3.4",
                        privateIp = "10.0.2.100",
                        alias = "app0",
                        availabilityZone = "us-west-2a",
                    ),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                        val clusterState =
                            ClusterState(
                                name = "test-cluster",
                                clusterId = "test-123",
                                versions = mutableMapOf(),
                                hosts = testHosts,
                            )
                        whenever(it.load()).thenReturn(clusterState)
                        whenever(it.exists()).thenReturn(true)
                    }
                }
            },
        )

    @BeforeEach
    fun captureStdout() {
        System.setOut(PrintStream(stdout))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
        stdout.reset()
    }

    private fun capturedOutput() = stdout.toString().trim()

    @Test
    fun `returns public IP by default`() {
        val command = Ip()
        command.host = "db0"
        command.execute()
        assertThat(capturedOutput()).isEqualTo("54.1.2.3")
    }

    @Test
    fun `returns public IP when --public flag is set`() {
        val command = Ip()
        command.host = "db0"
        command.publicIp = true
        command.execute()
        assertThat(capturedOutput()).isEqualTo("54.1.2.3")
    }

    @Test
    fun `returns private IP when --private flag is set`() {
        val command = Ip()
        command.host = "db0"
        command.privateIp = true
        command.execute()
        assertThat(capturedOutput()).isEqualTo("10.0.1.100")
    }

    @Test
    fun `finds host across different server types`() {
        val command = Ip()
        command.host = "app0"
        command.privateIp = true
        command.execute()
        assertThat(capturedOutput()).isEqualTo("10.0.2.100")
    }

    @Test
    fun `returns correct IP for second cassandra node`() {
        val command = Ip()
        command.host = "db1"
        command.privateIp = true
        command.execute()
        assertThat(capturedOutput()).isEqualTo("10.0.1.101")
    }

    @Test
    fun `throws error when host not found`() {
        val command = Ip()
        command.host = "nonexistent"
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Host not found: nonexistent")
    }

    @Test
    fun `throws error when no host alias provided`() {
        val command = Ip()
        command.host = ""
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Host not found:")
    }
}
