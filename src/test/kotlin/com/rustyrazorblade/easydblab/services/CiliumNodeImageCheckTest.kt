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
 * The check runs over SSH on each node, so [RemoteOperationsService] is stubbed at that boundary:
 * each node answers with the required paths it lacks, one per line.
 */
class CiliumNodeImageCheckTest {
    private val remoteOps: RemoteOperationsService = mock()
    private val check = CiliumNodeImageCheck(remoteOps)

    private val control = ClusterHost(publicIp = "", privateIp = "10.0.0.1", alias = "control0", availabilityZone = "a")
    private val db0 = ClusterHost(publicIp = "", privateIp = "10.0.0.2", alias = "db0", availabilityZone = "a")
    private val db1 = ClusterHost(publicIp = "", privateIp = "10.0.0.3", alias = "db1", availabilityZone = "a")

    private fun answer(
        node: ClusterHost,
        missing: List<String>,
    ) {
        whenever(remoteOps.executeRemotely(eq(node.toHost()), any(), any(), anyOrNull()))
            .thenReturn(Response(missing.joinToString(separator = "") { "$it\n" }))
    }

    @Test
    fun `a node whose image has every fix is not reported`() {
        answer(control, emptyList())
        answer(db0, emptyList())

        assertThat(check.nodesMissingFixes(listOf(control, db0))).isEmpty()
    }

    @Test
    fun `a node whose image lacks a fix is reported with exactly what it lacks`() {
        answer(control, emptyList())
        answer(db0, CiliumNodeImageCheck.REQUIRED_FILES)
        answer(db1, listOf(CiliumNodeImageCheck.REQUIRED_FILES.last()))

        assertThat(check.nodesMissingFixes(listOf(control, db0, db1))).containsExactly(
            NodeMissingCiliumFixes(node = "db0", missingFiles = CiliumNodeImageCheck.REQUIRED_FILES),
            NodeMissingCiliumFixes(node = "db1", missingFiles = listOf(CiliumNodeImageCheck.REQUIRED_FILES.last())),
        )
    }

    @Test
    fun `the node is asked about every file the base image bakes for Cilium`() {
        answer(control, emptyList())

        check.nodesMissingFixes(listOf(control))

        val command = argumentCaptor<String>()
        verify(remoteOps).executeRemotely(eq(control.toHost()), command.capture(), any(), anyOrNull())
        assertThat(CiliumNodeImageCheck.REQUIRED_FILES).containsExactlyInAnyOrder(
            "/etc/systemd/network/05-cilium-eni-primary.network",
            "/etc/systemd/network/06-cilium-eni-unmanaged.network",
            "/etc/cloud/cloud.cfg.d/90-easydblab-no-network-hotplug.cfg",
        )
        assertThat(command.firstValue).contains(*CiliumNodeImageCheck.REQUIRED_FILES.toTypedArray())
    }

    @Test
    fun `output that is not a required path does not count as a missing fix`() {
        whenever(remoteOps.executeRemotely(eq(db0.toHost()), any(), any(), anyOrNull()))
            .thenReturn(Response("Warning: Permanently added '10.0.0.2' to the list of known hosts.\n"))

        assertThat(check.nodesMissingFixes(listOf(db0))).isEmpty()
    }

    @Test
    fun `a node that cannot be reached fails the check instead of passing it`() {
        whenever(remoteOps.executeRemotely(any(), any(), any(), anyOrNull())).thenThrow(IllegalStateException("ssh: connection refused"))

        assertThatThrownBy { check.nodesMissingFixes(listOf(db0)) }.hasMessageContaining("connection refused")
    }
}
