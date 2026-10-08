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
class EcrCredentialProviderNodeCheckTest {
    private val remoteOps: RemoteOperationsService = mock()
    private val check = EcrCredentialProviderNodeCheck(remoteOps)

    private val control = ClusterHost(publicIp = "", privateIp = "10.0.0.1", alias = "control0", availabilityZone = "a")
    private val db0 = ClusterHost(publicIp = "", privateIp = "10.0.0.2", alias = "db0", availabilityZone = "a")
    private val app0 = ClusterHost(publicIp = "", privateIp = "10.0.0.3", alias = "app0", availabilityZone = "a")

    private fun answer(
        node: ClusterHost,
        missing: List<String>,
    ) {
        whenever(remoteOps.executeRemotely(eq(node.toHost()), any(), any(), anyOrNull()))
            .thenReturn(Response(missing.joinToString(separator = "") { "$it\n" }))
    }

    @Test
    fun `nodes that carry the provider are not reported, and one that lacks it is reported with what it lacks`() {
        answer(control, emptyList())
        answer(db0, EcrCredentialProviderNodeCheck.REQUIRED_FILES)
        answer(app0, listOf(EcrCredentialProviderNodeCheck.REQUIRED_FILES.last()))

        assertThat(check.nodesMissingProvider(listOf(control, db0, app0))).containsExactly(
            NodeMissingFiles(node = "db0", missingFiles = EcrCredentialProviderNodeCheck.REQUIRED_FILES),
            NodeMissingFiles(node = "app0", missingFiles = listOf(EcrCredentialProviderNodeCheck.REQUIRED_FILES.last())),
        )
    }

    @Test
    fun `the node is asked about the binary and the config the kubelet reads`() {
        answer(control, emptyList())

        check.nodesMissingProvider(listOf(control))

        val command = argumentCaptor<String>()
        verify(remoteOps).executeRemotely(eq(control.toHost()), command.capture(), any(), anyOrNull())
        assertThat(EcrCredentialProviderNodeCheck.REQUIRED_FILES).containsExactly(
            "/var/lib/rancher/credentialprovider/bin/ecr-credential-provider",
            "/var/lib/rancher/credentialprovider/config.yaml",
        )
        assertThat(command.firstValue).contains(*EcrCredentialProviderNodeCheck.REQUIRED_FILES.toTypedArray())
    }

    @Test
    fun `output that is not a required path does not count as a missing file`() {
        whenever(remoteOps.executeRemotely(eq(db0.toHost()), any(), any(), anyOrNull()))
            .thenReturn(Response("Warning: Permanently added '10.0.0.2' to the list of known hosts.\n"))

        assertThat(check.nodesMissingProvider(listOf(db0))).isEmpty()
    }

    @Test
    fun `a node that cannot be reached fails the check instead of passing it`() {
        whenever(remoteOps.executeRemotely(any(), any(), any(), anyOrNull())).thenThrow(IllegalStateException("ssh: connection refused"))

        assertThatThrownBy { check.nodesMissingProvider(listOf(db0)) }.hasMessageContaining("connection refused")
    }
}
