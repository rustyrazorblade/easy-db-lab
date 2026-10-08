package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * `up` checks every node for the kubelet ECR credential provider before K3s starts, so a node
 * launched from a base AMI that predates it fails up front, not when a pod on it cannot pull an
 * ECR image. Shares the happy-path fixture with [UpTest] through [UpTestFixture].
 */
class UpCredentialProviderTest : UpTestFixture() {
    private fun captureEvents(): List<Event> {
        val emitted = mutableListOf<Event>()
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    emitted += envelope.event
                }

                override fun close() = Unit
            },
        )
        return emitted
    }

    @Test
    fun `up fails before K3s starts when a node lacks the credential provider, naming the nodes and the files`() {
        missingCredentialProviderFiles["db0"] = Constants.K3s.ECR_CREDENTIAL_PROVIDER_FILES
        missingCredentialProviderFiles["app0"] = listOf(Constants.K3s.ECR_CREDENTIAL_PROVIDER_FILES.last())
        val emitted = captureEvents()

        assertThatThrownBy { newUp().execute() }.hasMessageContaining("db0").hasMessageContaining("build-image")

        val failure = emitted.filterIsInstance<Event.K3s.NodeImageMissingCredentialProvider>().single()
        assertThat(failure.nodes).containsExactly("db0", "app0")
        assertThat(failure.missingFiles).isEqualTo(Constants.K3s.ECR_CREDENTIAL_PROVIDER_FILES)
        assertThat(failure.isError()).isTrue()
        assertThat(failure.toDisplayString()).contains("db0", "app0", *Constants.K3s.ECR_CREDENTIAL_PROVIDER_FILES.toTypedArray())
        verify(mockK3sClusterService, never()).setupCluster(any())
    }

    @Test
    fun `up checks every node and starts K3s when all carry the provider`() {
        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(credentialProviderCheckedAliases).containsExactlyInAnyOrder("control0", "db0", "app0")
        verify(mockK3sClusterService).setupCluster(any())
    }
}
