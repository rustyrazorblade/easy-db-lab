package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.apache.sshd.common.SshException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The SSH readiness wait at scale: each host is probed until it answers once, a host that answered
 * is never probed again, and one overall time budget bounds the wait however many hosts there are.
 */
class SshReadinessWaitTest {
    private val probes = ConcurrentHashMap<String, Int>()

    /** How many probes each alias fails before it answers; absent means it answers at once. */
    private val failuresBeforeReady = ConcurrentHashMap<String, Int>()

    private val remoteOps: RemoteOperationsService =
        mock {
            on { executeRemotely(any(), any(), any(), any()) } doAnswer { invocation ->
                val alias = invocation.getArgument<Host>(0).alias
                val attempt = probes.merge(alias, 1, Int::plus) ?: 1
                if (attempt <= failuresBeforeReady.getOrDefault(alias, 0)) {
                    throw SshException("Connection refused")
                }
                Response("1")
            }
        }

    private fun host(alias: String) =
        ClusterHost(publicIp = "54.0.0.1", privateIp = "10.0.0.1", alias = alias, availabilityZone = "us-west-2a")

    private val hosts =
        mapOf(
            ServerType.Control to listOf(host("control0")),
            ServerType.Cassandra to (0 until 10).map { host("db$it") },
        )

    private fun waiter(timeout: Duration = Duration.ofSeconds(10)) =
        SshReadinessWait(
            HostOperationsService(mock<ClusterStateManager>()),
            remoteOps,
            EventBus(),
            startupDelay = Duration.ZERO,
            retryInterval = Duration.ofMillis(1),
            timeout = timeout,
        )

    @Test
    fun `a host that answered is not probed again while another is still starting`() {
        failuresBeforeReady["db7"] = 3

        waiter().await(hosts, databaseHostFilter = "")

        assertThat(probes["db7"]).isEqualTo(4)
        assertThat(probes.filterKeys { it != "db7" }.values).containsOnly(1)
        assertThat(probes.keys).hasSize(11)
    }

    @Test
    fun `a host that never answers fails the wait once the time budget is spent, naming it`() {
        failuresBeforeReady["db3"] = Int.MAX_VALUE

        assertThatThrownBy { waiter(timeout = Duration.ofMillis(200)).await(hosts, databaseHostFilter = "") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("db3")
            .hasMessageNotContaining("db4")
    }

    @Test
    fun `a failure that waiting cannot fix fails at once`() {
        val broken: RemoteOperationsService =
            mock {
                on { executeRemotely(any(), any(), any(), any()) } doAnswer { error("No such file or directory") }
            }
        val wait =
            SshReadinessWait(
                HostOperationsService(mock<ClusterStateManager>()),
                broken,
                EventBus(),
                startupDelay = Duration.ZERO,
                retryInterval = Duration.ofMillis(1),
                timeout = Duration.ofSeconds(10),
            )

        assertThatThrownBy { wait.await(hosts, databaseHostFilter = "") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No such file or directory")
    }

    @Test
    fun `the database filter scopes db hosts but never the control node`() {
        waiter().await(hosts, databaseHostFilter = "db2")

        assertThat(probes.keys).containsExactlyInAnyOrder("control0", "db2")
    }
}
