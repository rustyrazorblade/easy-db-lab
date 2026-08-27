package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.profiling.ProfilingConfig
import com.rustyrazorblade.easydblab.services.CassandraProfilingService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for [SetupInstance]'s profiling seeding.
 *
 * Cluster-up is the only moment that knows the Pyroscope address and the cluster name, so it is
 * where desired profiling state is seeded — which is what makes a freshly provisioned cluster
 * profile CPU with no operator action.
 */
class SetupInstanceTest : BaseKoinTest() {
    private lateinit var profilingService: CassandraProfilingService

    private val hosts =
        mapOf(
            ServerType.Control to
                listOf(
                    ClusterHost(
                        publicIp = "54.0.0.1",
                        privateIp = "10.0.1.5",
                        alias = "control0",
                        availabilityZone = "us-west-2a",
                    ),
                ),
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
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(
                            ClusterState(
                                name = "test-cluster",
                                clusterId = "abc123",
                                versions = mutableMapOf(),
                                hosts = hosts,
                            ),
                        )
                        whenever(it.exists()).thenReturn(true)
                    }
                }
                single { HostOperationsService(get()) }
                single<CassandraProfilingService> { mock<CassandraProfilingService>().also { profilingService = it } }
            },
        )

    @BeforeEach
    fun setup() {
        profilingService = getKoin().get()
    }

    @Test
    fun `seeds every Cassandra node with profiling enabled and no other node type`() {
        SetupInstance().execute()

        val targets = argumentCaptor<Host>()
        val configs = argumentCaptor<ProfilingConfig>()
        verify(profilingService, times(2)).writeDesiredState(targets.capture(), configs.capture())

        assertThat(targets.allValues.map { it.alias }).containsExactly("db0", "db1")
        val config = configs.firstValue
        assertThat(config.enabled).isTrue()
        assertThat(config.asprofArgs).isEqualTo(Constants.Profiling.DEFAULT_ASPROF_ARGS)
        assertThat(config.pyroscopeUrl).isEqualTo("http://10.0.1.5:${Constants.K8s.PYROSCOPE_PORT}")
        assertThat(config.clusterName).isEqualTo("test-cluster-abc123")
    }
}
