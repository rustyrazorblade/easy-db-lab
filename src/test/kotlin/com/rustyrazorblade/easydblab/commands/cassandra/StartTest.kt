package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.CassandraService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.SidecarService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class StartTest : BaseKoinTest() {
    private lateinit var mockCassandraService: CassandraService
    private lateinit var mockSidecarService: SidecarService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var hostOperationsService: HostOperationsService
    private lateinit var outputHandler: BufferedOutputHandler

    private val testCassandraHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.1.1",
            alias = "db0",
            availabilityZone = "us-west-2a",
            instanceId = "i-db0",
        )

    private val testClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            initConfig = InitConfig(region = "us-west-2"),
            hosts =
                mapOf(
                    ServerType.Cassandra to listOf(testCassandraHost),
                    ServerType.Control to
                        listOf(
                            ClusterHost(
                                publicIp = "54.1.2.5",
                                privateIp = "10.0.1.3",
                                alias = "control0",
                                availabilityZone = "us-west-2a",
                                instanceId = "i-control0",
                            ),
                        ),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<CassandraService> { mockCassandraService }
                single<SidecarService> { mockSidecarService }
                single<ClusterStateManager> { mockClusterStateManager }
                single { hostOperationsService }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockCassandraService = mock()
        mockSidecarService = mock()
        mockClusterStateManager = mock()
        hostOperationsService = HostOperationsService(mockClusterStateManager)
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
        whenever(mockCassandraService.start(any(), any())).thenReturn(Result.success(Unit))
        whenever(mockSidecarService.start(any())).thenReturn(Result.success(Unit))
    }

    @Test
    fun `execute starts cassandra and sidecar on all nodes`() {
        val command = Start()
        command.execute()

        verify(mockCassandraService).start(any(), eq(true))
        verify(mockSidecarService).start(any())
    }

    @Test
    fun `execute outputs starting message`() {
        val command = Start()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Starting")
    }

    @Test
    fun `execute does not start axon-agent when not configured`() {
        // User in core test modules has blank axonOpsOrg and axonOpsKey
        val command = Start()
        command.execute()

        // No axon-agent message should appear
        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).doesNotContain("axon-agent")
    }

    @Test
    fun `execute starts axon-agent when configured`() {
        // Override user config with axon ops settings
        val userWithAxon =
            User(
                email = "test@example.com",
                region = "us-west-2",
                keyName = "test-key",
                awsProfile = "",
                awsAccessKey = "test-access-key",
                awsSecret = "test-secret",
                axonOpsOrg = "my-org",
                axonOpsKey = "my-key",
            )
        getKoin().declare(userWithAxon)

        val command = Start()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("axon-agent")
    }
}
