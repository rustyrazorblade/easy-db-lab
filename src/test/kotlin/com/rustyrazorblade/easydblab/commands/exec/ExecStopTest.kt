package com.rustyrazorblade.easydblab.commands.exec

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests the unit `exec stop` stops. The remote operations are mocked because `systemctl` exists
 * only on the node; the unit name must be the one `exec run` gave the same `--name`.
 */
class ExecStopTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockRemoteOps: RemoteOperationsService = mock()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single<RemoteOperationsService> { mockRemoteOps }
                single { HostOperationsService(mockClusterStateManager) }
            },
        )

    @BeforeEach
    fun setupState() {
        val db0 =
            ClusterHost(
                publicIp = "54.1.2.1",
                privateIp = "10.0.1.1",
                alias = "db0",
                availabilityZone = "us-west-2a",
                instanceId = "i-db0",
            )
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                initConfig = InitConfig(region = "us-west-2"),
                hosts = mapOf(ServerType.Cassandra to listOf(db0)),
            ),
        )
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any())).thenReturn(Response("active"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["my tool's run", "edl-exec-my tool's run.service"])
    fun `a stop names the unit exec run made for the same name`(name: String) {
        ExecStop()
            .apply {
                this.name = name
                serverType = ServerType.Cassandra
            }.execute()

        val sent = argumentCaptor<String>()
        verify(mockRemoteOps, atLeastOnce()).executeRemotely(any(), sent.capture(), any(), any())
        assertThat(sent.allValues).containsExactly(
            "sudo systemctl is-active edl-exec-my-tool-s-run || true",
            "sudo systemctl stop edl-exec-my-tool-s-run",
        )
    }
}
