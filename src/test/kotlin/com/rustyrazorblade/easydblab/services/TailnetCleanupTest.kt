package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.TestProfiles
import com.rustyrazorblade.easydblab.configuration.profile.Tailscale
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [TailnetCleanup] decides what each tailnet removal means for `down`, from the profile's
 * credentials and Tailscale's answer. The Tailscale API is the one mocked collaborator.
 */
class TailnetCleanupTest {
    private val tailscale: TailscaleService = mock()
    private val emitted = mutableListOf<Event>()
    private val eventBus =
        EventBus().apply {
            addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        emitted.add(envelope.event)
                    }

                    override fun close() = Unit
                },
            )
        }

    private fun cleanup(
        clientId: String = "client-id",
        clientSecret: String = "client-secret",
    ) = TailnetCleanup(
        tailscale,
        TestProfiles.accessOf(TestProfiles.profile(tailscale = Tailscale.orNull(clientId, clientSecret))),
        eventBus,
    )

    @BeforeEach
    fun clearEvents() = emitted.clear()

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["  "])
    fun `a device with no recorded ID has nothing to remove`(deviceId: String?) {
        assertThat(cleanup().removeDevice(deviceId)).isEqualTo(DeviceRemoval.NoneRecorded)

        verify(tailscale, never()).deleteDevice(any(), any(), any())
        assertThat(emitted).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["client-id", "client-secret", "both"])
    fun `a device cannot be removed without both OAuth credentials, and the reason says how to fix it`(missing: String) {
        val cleanup =
            when (missing) {
                "client-id" -> cleanup(clientId = "")
                "client-secret" -> cleanup(clientSecret = "")
                else -> cleanup(clientId = "", clientSecret = "")
            }

        val removal = cleanup.removeDevice("nControl0CNTRL")

        assertThat(removal).isInstanceOf(DeviceRemoval.Failed::class.java)
        assertThat((removal as DeviceRemoval.Failed).reason)
            .contains("nControl0CNTRL", "no Tailscale OAuth credentials", "easy-db-lab profile setup")
        verify(tailscale, never()).deleteDevice(any(), any(), any())
        assertThat(emitted).isEmpty()
    }

    @Test
    fun `a removed device is reported once`() {
        val removal = cleanup().removeDevice("nControl0CNTRL")

        assertThat(removal).isEqualTo(DeviceRemoval.Removed("nControl0CNTRL"))
        verify(tailscale).deleteDevice("client-id", "client-secret", "nControl0CNTRL")
        assertThat(emitted).containsExactly(Event.Tailscale.DeviceDeleted("nControl0CNTRL"))
    }

    @Test
    fun `a device Tailscale refuses to delete fails with its error and reports nothing`() {
        doThrow(TailscaleApiException("403 forbidden")).whenever(tailscale).deleteDevice(any(), any(), any())

        val removal = cleanup().removeDevice("nControl0CNTRL")

        assertThat((removal as DeviceRemoval.Failed).reason)
            .contains("nControl0CNTRL", "was not removed from the tailnet", "403 forbidden")
        assertThat(emitted).isEmpty()
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["  "])
    fun `an auth key with no recorded ID has nothing to delete`(keyId: String?) {
        assertThat(cleanup().deleteAuthKey(keyId)).isEqualTo(AuthKeyRemoval.NoneRecorded)

        verify(tailscale, never()).deleteAuthKey(any(), any(), any())
    }

    @Test
    fun `an auth key is kept when the profile has no OAuth credentials`() {
        assertThat(cleanup(clientSecret = "").deleteAuthKey("k123")).isEqualTo(AuthKeyRemoval.NoCredentials("k123"))

        verify(tailscale, never()).deleteAuthKey(any(), any(), any())
    }

    @Test
    fun `a deleted auth key is reported once`() {
        assertThat(cleanup().deleteAuthKey("k123")).isEqualTo(AuthKeyRemoval.Removed("k123"))

        verify(tailscale).deleteAuthKey("client-id", "client-secret", "k123")
        assertThat(emitted).containsExactly(Event.Tailscale.AuthKeyDeleted("k123"))
    }

    @Test
    fun `an auth key Tailscale refuses to delete is reported as failed, not thrown`() {
        doThrow(TailscaleApiException("500 oops")).whenever(tailscale).deleteAuthKey(any(), any(), any())

        assertThat(cleanup().deleteAuthKey("k123")).isEqualTo(AuthKeyRemoval.Failed("k123", "500 oops"))
        assertThat(emitted).isEmpty()
    }
}
