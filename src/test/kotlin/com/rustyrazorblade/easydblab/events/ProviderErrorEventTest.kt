package com.rustyrazorblade.easydblab.events

import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The provider's own message is what tells the user why a call failed, and the remedy can be a
 * best guess (a 403 may be a bucket policy), so the message must come before the remedy.
 */
class ProviderErrorEventTest {
    @Test
    fun `the provider message is printed before the remedy`() {
        val text =
            Event.Command
                .ProviderError("AWS", ProviderErrorKind.PERMISSION_DENIED, "User is not authorized to s3:PutObject", "Attach the policy")
                .toDisplayString()

        assertThat(text.indexOf("User is not authorized to s3:PutObject"))
            .isGreaterThanOrEqualTo(0)
            .isLessThan(text.indexOf("Attach the policy"))
    }
}
