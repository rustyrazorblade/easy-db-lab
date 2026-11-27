package com.rustyrazorblade.easydblab.configuration.profile

import com.rustyrazorblade.easydblab.TestProfiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The rule for the optional feature blocks: a block is enabled only when both of its values are
 * non-blank, and [Tailscale.orNull] / [AxonOps.orNull] keep a half-set pair so setup can prefill it.
 *
 * Control: make `enabled` check only the first value; the rows with a blank second value fail.
 */
class ProfileFeatureBlocksTest {
    @ParameterizedTest(name = "[{index}] ''{0}'' / ''{1}'' -> enabled={2}, kept={3}")
    @MethodSource("pairs")
    fun `tailscale is enabled only with both values and kept when either is set`(
        first: String,
        second: String,
        enabled: Boolean,
        kept: Boolean,
    ) {
        val block = Tailscale.orNull(first, second)
        val profile = TestProfiles.profile(tailscale = block)

        assertThat(block != null).isEqualTo(kept)
        assertThat(profile.isTailscaleEnabled).isEqualTo(enabled)
    }

    @ParameterizedTest(name = "[{index}] ''{0}'' / ''{1}'' -> enabled={2}, kept={3}")
    @MethodSource("pairs")
    fun `axonops is enabled only with both values and kept when either is set`(
        first: String,
        second: String,
        enabled: Boolean,
        kept: Boolean,
    ) {
        val block = AxonOps.orNull(first, second)
        val profile = TestProfiles.profile(axonOps = block)

        assertThat(block != null).isEqualTo(kept)
        assertThat(profile.isAxonOpsEnabled).isEqualTo(enabled)
    }

    companion object {
        @JvmStatic
        fun pairs(): List<Arguments> =
            listOf(
                Arguments.of("id", "secret", true, true),
                Arguments.of("", "secret", false, true),
                Arguments.of("id", "", false, true),
                Arguments.of("  ", "secret", false, true),
                Arguments.of("id", "  ", false, true),
                Arguments.of("", "", false, false),
                Arguments.of(" ", " ", false, false),
            )
    }
}
