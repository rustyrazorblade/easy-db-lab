package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The probe runs over SSH on each node, so [RemoteOperationsService] is stubbed at that boundary:
 * each node answers with the required paths it lacks, one per line.
 */
class NodeFileCheckTest {
    private val remoteOps: RemoteOperationsService = mock()
    private val check = NodeFileCheck(remoteOps)
    private val required = listOf("/etc/a.conf", "/var/lib/b/bin/tool")

    private val node0 = ClusterHost(publicIp = "", privateIp = "10.0.0.1", alias = "node0", availabilityZone = "a")
    private val node1 = ClusterHost(publicIp = "", privateIp = "10.0.0.2", alias = "node1", availabilityZone = "a")

    private fun answer(
        node: ClusterHost,
        output: String,
    ) {
        whenever(remoteOps.executeRemotely(eq(node.toHost()), any(), any(), anyOrNull())).thenReturn(Response(output))
    }

    @Test
    fun `each node is reported with exactly the files it lacks, and a complete node is not reported`() {
        answer(node0, "")
        answer(node1, "/var/lib/b/bin/tool\n")

        assertThat(check.nodesMissing(listOf(node0, node1), required))
            .containsExactly(NodeMissingFiles(node = "node1", missingFiles = listOf("/var/lib/b/bin/tool")))
    }

    @Test
    fun `the node is asked about every required file`() {
        answer(node0, "")

        check.nodesMissing(listOf(node0), required)

        val command = argumentCaptor<String>()
        verify(remoteOps).executeRemotely(eq(node0.toHost()), command.capture(), any(), anyOrNull())
        assertThat(command.firstValue).contains(*required.toTypedArray())
    }

    @Test
    fun `output that is not a required path does not count as a missing file`() {
        answer(node0, "Warning: Permanently added '10.0.0.1' to the list of known hosts.\n")

        assertThat(check.nodesMissing(listOf(node0), required)).isEmpty()
    }

    @Test
    fun `a node that cannot be reached fails the check instead of passing it`() {
        whenever(remoteOps.executeRemotely(any(), any(), any(), anyOrNull())).thenThrow(IllegalStateException("ssh: connection refused"))

        assertThatThrownBy { check.nodesMissing(listOf(node0), required) }.hasMessageContaining("connection refused")
    }
}
