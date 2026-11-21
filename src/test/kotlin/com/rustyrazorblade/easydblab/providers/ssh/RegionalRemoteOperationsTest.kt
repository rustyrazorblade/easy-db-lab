package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.ssh.ISSHClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.mock
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * [RegionalRemoteOperations] decides which route reaches a region's hosts. Under direct, or in the
 * profile region, the shared service already reaches them; under ssm, another region gets a route
 * of its own. What it opens it must close: the connections when any were opened, else the route.
 */
internal class RegionalRemoteOperationsTest {
    private val shared: RemoteOperationsService = mock()
    private val routesBuilt = AtomicInteger()
    private val route = CountingRoute()
    private val connections = CountingConnections()

    private fun operations(transport: SshTransport): RegionalRemoteOperations =
        RegionalRemoteOperations.forProfile(
            shared = shared,
            transport = transport,
            profileRegion = PROFILE_REGION,
            ssmRouteFor = {
                routesBuilt.incrementAndGet()
                route
            },
            connectionsOver = { connections },
        )

    @ParameterizedTest(name = "{0} in {1} uses its own route: {2}")
    @MethodSource("routing")
    fun `only ssm outside the profile region gets a route of its own`(
        transport: SshTransport,
        region: String,
        ownRoute: Boolean,
    ) {
        operations(transport).forRegion(region).use { remote ->
            when (remote) {
                is RegionRemote.Shared -> assertThat(remote.operations).isSameAs(shared)
                is RegionRemote.Own -> assertThat(remote.operations).isNotSameAs(shared)
            }
            assertThat(remote is RegionRemote.Own).isEqualTo(ownRoute)
        }
        assertThat(routesBuilt).hasValue(if (ownRoute) 1 else 0)
    }

    @Test
    fun `closing a region route that no connection used closes the route`() {
        operations(SshTransport.Ssm).forRegion(OTHER_REGION).close()

        assertThat(route.closed).hasValue(1)
        assertThat(connections.stopped).hasValue(0)
    }

    @Test
    fun `closing a region route that was used stops its connections`() {
        operations(SshTransport.Ssm).forRegion(OTHER_REGION).use { remote ->
            remote.operations.download(HOST, "/etc/hostname", Path.of("unused"))
        }

        assertThat(connections.stopped).hasValue(1)
        assertThat(connections.connectionsAsked).hasValue(1)
    }

    @Test
    fun `closing the shared service leaves it open`() {
        operations(SshTransport.Direct).forRegion(OTHER_REGION).close()

        assertThat(route.closed).hasValue(0)
        assertThat(connections.stopped).hasValue(0)
    }

    /** A route that counts how often it was closed. */
    private class CountingRoute : SshRoute {
        val closed = AtomicInteger()

        override fun endpoint(host: Host): SshEndpoint = SshEndpoint(host.public, 22)

        override fun proxyCommand(host: Host): String? = null

        override val tunnelVerifyAttempts: Int = 1

        override val authTimeout = null

        override fun close() {
            closed.incrementAndGet()
        }
    }

    /** Connections that count requests and stops, handing out a mock client. */
    private class CountingConnections : SSHConnectionProvider {
        val stopped = AtomicInteger()
        val connectionsAsked = AtomicInteger()

        override fun getConnection(host: Host): ISSHClient {
            connectionsAsked.incrementAndGet()
            return mock()
        }

        override fun discard(host: Host) = Unit

        override fun stop() {
            stopped.incrementAndGet()
        }
    }

    companion object {
        private const val PROFILE_REGION = "us-west-2"
        private const val OTHER_REGION = "us-east-2"
        private val HOST =
            Host(public = "3.1.2.3", private = "10.0.0.7", alias = "builder", availabilityZone = "us-east-2a", instanceId = "i-0abc")

        @JvmStatic
        fun routing(): List<Arguments> =
            listOf(
                Arguments.of(SshTransport.Direct, PROFILE_REGION, false),
                Arguments.of(SshTransport.Direct, OTHER_REGION, false),
                Arguments.of(SshTransport.Ssm, PROFILE_REGION, false),
                Arguments.of(SshTransport.Ssm, OTHER_REGION, true),
            )
    }
}
