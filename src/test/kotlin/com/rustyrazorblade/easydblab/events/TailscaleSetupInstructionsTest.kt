package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * `profile setup` tells the user which scopes to give the Tailscale OAuth client. They must be the
 * ones `tailscale start` needs (`auth_keys`) and `down`/`tailscale stop` report when missing
 * (`devices:core`), by their current names.
 */
class TailscaleSetupInstructionsTest {
    @ParameterizedTest
    @MethodSource("instructions")
    fun `the Tailscale setup instructions name the OAuth scopes by their current names`(event: Event) {
        assertThat(event.toDisplayString())
            .contains("auth_keys")
            .contains("devices:core")
            .doesNotContainIgnoringCase("Devices: Write")
    }

    companion object {
        @JvmStatic
        fun instructions(): List<Event> =
            listOf(
                Event.Setup.TailscaleSetupInstructions(defaultTag = "tag:easy-db-lab"),
                Event.Setup.TailscaleConfigHeader(defaultTag = "tag:easy-db-lab"),
            )
    }
}
