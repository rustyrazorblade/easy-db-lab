package com.rustyrazorblade.easydblab.configuration.stress

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [StressCluster.from] reads the facts a stress Job is built from out of the cluster state.
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
    fun `reads the cluster label, the tenant and the app role selector`() {
        val cluster = StressCluster.from(state())

        assertThat(cluster)
            .isEqualTo(
                StressCluster(
                    clusterName = "lab-abc",
                    tenant = "default",
                    telemetryRedirect = null,
                    appSelector = RoleSelectors.OWNED_NODES.app,
                    namespace = TestScopes.EC2.namespace,
                ),
            )
    }

    @Test
    fun `carries the namespace init recorded`() {
        val cluster = StressCluster.from(state(initConfig = InitConfig(namespace = "lab-a")))

        assertThat(cluster.namespace).isEqualTo(K8sNamespace("lab-a"))
        assertThat(cluster.serviceDns.otlpHttpEndpoint()).isEqualTo("http://otel-collector.lab-a.svc:4318")
    }

    @Test
    fun `carries the tenant and the telemetry redirect of the init config`() {
        val redirect = TelemetryRedirect(profiles = "http://obs.example.com:4040")

        val cluster = StressCluster.from(state(initConfig = InitConfig(tenant = "acme", telemetryRedirect = redirect)))

        assertThat(cluster.tenant).isEqualTo("acme")
        assertThat(cluster.telemetryRedirect).isEqualTo(redirect)
    }
}
