package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** How teardown outcomes are classified for the console: failures go to stderr. */
class TeardownEventTest {
    /** `down` exits non-zero on these, so they are errors like every other failure event. */
    @Test
    fun `a teardown that completed with errors is an error event`() {
        val event = Event.Teardown.CompletedWithErrors(listOf("Tailscale device node-1 was not removed"))

        assertThat(event.isError()).isTrue()
    }
}
