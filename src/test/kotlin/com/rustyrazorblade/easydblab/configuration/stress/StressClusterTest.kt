package com.rustyrazorblade.easydblab.configuration.stress

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [StressCluster.from] reads the facts a stress Job is built from out of the cluster state, and
 * refuses a state that cannot say where the Job runs.
 */
class StressClusterTest {
    private val controlHost =
        ClusterHost(publicIp = "54.1.2.3", privateIp = "10.0.1.5", alias = "control0", availabilityZone = "us-west-2a")

    private fun state(
        infrastructure: InfrastructureState? = InfrastructureState(vpcId = "vpc-1", region = "eu-west-1"),
        hosts: Map<ServerType, List<ClusterHost>> = mapOf(ServerType.Control to listOf(controlHost)),
        initConfig: InitConfig? = null,
    ) = ClusterState(
        name = "lab",
        clusterId = "abc",
        infrastructure = infrastructure,
        hosts = hosts,
        initConfig = initConfig,
    )

    @Test
    fun `reads the region and the cluster label`() {
        val cluster = StressCluster.from(state())

        assertThat(cluster)
            .isEqualTo(
                StressCluster(
                    region = "eu-west-1",
                    clusterName = "lab-abc",
                    tenant = "default",
                    telemetryRedirect = null,
                ),
            )
    }

    @Test
    fun `carries the tenant and the telemetry redirect of the init config`() {
        val redirect = TelemetryRedirect(profiles = "http://obs.example.com:4040")

        val cluster = StressCluster.from(state(initConfig = InitConfig(tenant = "acme", telemetryRedirect = redirect)))

        assertThat(cluster.tenant).isEqualTo("acme")
        assertThat(cluster.telemetryRedirect).isEqualTo(redirect)
    }

    @Test
    fun `fails naming the fix when the state has no infrastructure`() {
        assertThatThrownBy { StressCluster.from(state(infrastructure = null)) }
            .hasMessage("Infrastructure state missing region. Re-provision the cluster to fix this.")
    }
}
