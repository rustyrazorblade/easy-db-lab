package com.rustyrazorblade.easydblab.containers

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.providers.ssh.RegionalRemoteOperations
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.providers.ssh.SSHConnectionProvider
import com.rustyrazorblade.easydblab.providers.ssh.SshEndpoint
import com.rustyrazorblade.easydblab.providers.ssh.SshRoute
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * Tests [Packer.inBuildRegion]: a build's site and the remote operations that reach its builders
 * are both opened in the build region, never the profile region, and both are closed however the
 * build ends. Under `ssm` a session reaches only its own region, so a remote for the profile
 * region could not reach a builder in any other.
 */
class PackerBuildRegionTest {
    private companion object {
        const val PROFILE_REGION = "us-west-2"
        const val BUILD_REGION = "eu-west-3"
    }

    private val shared: RemoteOperationsService = mock()
    private val routeRegions = mutableListOf<String>()
    private val routesClosed = mutableListOf<String>()
    private val sitesOpened = mutableListOf<Pair<String, String>>()
    private var sitesClosed = 0

    private val build = PackerBuild("base.pkr.hcl", "0123456789ab", BUILD_REGION, maxAttempts = 3, keepOnError = false)

    private val sites =
        BuildSites { region, buildId ->
            sitesOpened += region to buildId
            object : BuildSite {
                override val region = region
                override val keyPairName = "key"
                override val builders: BuilderInstances = mock()

                override fun close() {
                    sitesClosed++
                }
            }
        }

    private val remotes =
        RegionalRemoteOperations.forProfile(
            shared = shared,
            transport = SshTransport.Ssm,
            profileRegion = PROFILE_REGION,
            ssmRouteFor = { region ->
                routeRegions += region
                RegionRoute(region, routesClosed)
            },
            connectionsOver = { mock<SSHConnectionProvider>() },
        )

    /** A route that records its region when it is closed. */
    private class RegionRoute(
        private val region: String,
        private val closed: MutableList<String>,
    ) : SshRoute {
        override fun endpoint(host: Host): SshEndpoint = SshEndpoint(host.public, 22)

        override fun proxyCommand(host: Host): String? = null

        override val tunnelVerifyAttempts: Int = 1

        override val authTimeout = null

        override fun close() {
            closed += region
        }
    }

    @Test
    fun `the site and the builders' remote are opened in the build region and closed after the build`() {
        val seen =
            Packer.inBuildRegion(build, sites, remotes) { site, remote ->
                assertThat(remote).isNotSameAs(shared)
                site.region
            }

        assertThat(seen).isEqualTo(BUILD_REGION)
        assertThat(sitesOpened).containsExactly(BUILD_REGION to build.buildId)
        assertThat(routeRegions).containsExactly(BUILD_REGION)
        assertThat(routesClosed).containsExactly(BUILD_REGION)
        assertThat(sitesClosed).isEqualTo(1)
    }

    @Test
    fun `a build that throws still closes the remote and the site`() {
        assertThatThrownBy { Packer.inBuildRegion(build, sites, remotes) { _, _ -> error("packer died") } }
            .hasMessage("packer died")

        assertThat(routesClosed).containsExactly(BUILD_REGION)
        assertThat(sitesClosed).isEqualTo(1)
    }

    @Test
    fun `a build in the profile region uses the shared remote`() {
        val local = build.copy(region = PROFILE_REGION)

        Packer.inBuildRegion(local, sites, remotes) { _, remote -> assertThat(remote).isSameAs(shared) }

        assertThat(routeRegions).isEmpty()
    }
}
