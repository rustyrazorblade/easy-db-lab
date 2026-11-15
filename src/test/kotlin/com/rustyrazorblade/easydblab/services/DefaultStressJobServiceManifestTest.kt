package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.client.utils.Serialization
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The Jobs `DefaultStressJobService` applies are byte-identical to the ones it rendered before the
 * manifest builder and the sidecar config were split out of it: the golden files under
 * `services/stress-golden/` were rendered by the pre-split service from this same cluster state.
 * The service loads the cluster state once per Job it builds.
 */
class DefaultStressJobServiceManifestTest : BaseKoinTest() {
    private val stateManager: ClusterStateManager = mock()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { stateManager }
                single { TemplateService(get(), get()) }
            },
        )

    private val config =
        StressJobConfig(
            jobName = "stress-golden",
            image = "ghcr.io/apache/cassandra-easy-stress:latest",
            args = listOf("run", "KeyValue", "-d", "1h"),
            contactPoints = "10.0.1.6,10.0.1.7",
            tags = mapOf("team" to "perf", "run" to "42"),
            promPort = 9500,
        )

    private fun state(
        redirect: TelemetryRedirect? = null,
        withInfrastructure: Boolean = true,
    ) = ClusterState(
        name = "test-cluster",
        clusterId = "golden-cluster-id",
        versions = mutableMapOf(),
        infrastructure = if (withInfrastructure) InfrastructureState(vpcId = "vpc-test", region = "us-west-2") else null,
        initConfig = InitConfig(region = "us-west-2", telemetryRedirect = redirect, tenant = "acme"),
        hosts =
            mapOf(
                ServerType.Control to
                    listOf(
                        ClusterHost(
                            publicIp = "54.123.45.67",
                            privateIp = "10.0.1.5",
                            alias = "control0",
                            availabilityZone = "us-west-2a",
                            instanceId = "i-control123",
                        ),
                    ),
            ),
    )

    private fun service() = DefaultStressJobService(mock(), stateManager, EventBus(), getKoin().get())

    private fun golden(name: String): String =
        requireNotNull(javaClass.getResource("/com/rustyrazorblade/easydblab/services/stress-golden/$name")) { "missing golden $name" }
            .readText()

    @Test
    fun `the stress Job is byte-identical to the pre-split rendering`() {
        whenever(stateManager.load()).thenReturn(state())

        assertThat(Serialization.asYaml(service().buildJob(config))).isEqualTo(golden("stress-job-local.yaml"))
        verify(stateManager, times(1)).load()
    }

    @Test
    fun `the stress Job with a telemetry redirect is byte-identical to the pre-split rendering`() {
        whenever(stateManager.load()).thenReturn(state(TelemetryRedirect(profiles = "http://obs.example.com:4040")))

        assertThat(Serialization.asYaml(service().buildJob(config))).isEqualTo(golden("stress-job-redirect.yaml"))
    }

    @Test
    fun `the command Job is byte-identical to the pre-split rendering and loads no state`() {
        val job = service().buildCommandJob("cmd-1700000000", "ghcr.io/apache/cassandra-easy-stress:latest", listOf("list"))

        assertThat(Serialization.asYaml(job)).isEqualTo(golden("command-job.yaml"))
        verify(stateManager, times(0)).load()
    }

    @Test
    fun `a state with no infrastructure fails the Job build with the re-provision message`() {
        whenever(stateManager.load()).thenReturn(state(withInfrastructure = false))

        assertThatThrownBy { service().buildJob(config) }
            .hasMessage("Infrastructure state missing region. Re-provision the cluster to fix this.")
    }
}
