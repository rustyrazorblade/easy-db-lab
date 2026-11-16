package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

/**
 * [NodeSetupService]'s app-node setup reads the region from the node it sets up, not from another
 * node, so a node's setup depends on no other node (design D2). The remote calls are mocked at the
 * SSH boundary; the uploaded `stress.env` is read as it is uploaded.
 */
class NodeSetupServiceTest {
    private val remoteOps: RemoteOperationsService = mock()
    private val service = NodeSetupService(remoteOps, mock(), EventBus())
    private val context = NodeSetupContext("10.0.1.100", "10.0.1.5", "test-abc", "default")
    private val uploads = mutableMapOf<String, MutableList<String>>()

    private fun host(
        alias: String,
        ip: String,
    ) = Host(public = "54.0.0.$ip", private = "10.0.2.$ip", alias = alias, availabilityZone = "us-west-2a")

    @BeforeEach
    fun setup() {
        whenever(remoteOps.executeRemotely(any(), any(), any(), any())).thenReturn(Response(""))
        whenever(remoteOps.upload(any(), any(), any())).thenAnswer {
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
}
