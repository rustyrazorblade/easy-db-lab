package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the rule that AxonOps needs both credentials.
 *
 * `Up` and `cassandra start` both skip AxonOps unless the org and the key are set, so a helper
 * that reads either one alone would report a profile as enabled while nothing runs at runtime.
 */
class UserAxonOpsTest {
    private fun user(
        org: String = "",
        key: String = "",
    ) = User(
        email = "test@example.com",
        region = "us-east-1",
        keyName = "test-key",
        awsProfile = "default",
        awsAccessKey = "",
        awsSecret = "",
        axonOpsOrg = org,
        axonOpsKey = key,
    )

    @Test
    fun `isAxonOpsEnabled returns true when both credentials are non-blank`() {
        assertThat(user(org = "acme", key = "axon-key").isAxonOpsEnabled()).isTrue()
    }

    @Test
    fun `isAxonOpsEnabled returns false when the org is blank`() {
        assertThat(user(org = "", key = "axon-key").isAxonOpsEnabled()).isFalse()
    }

    @Test
    fun `isAxonOpsEnabled returns false when the key is blank`() {
        assertThat(user(org = "acme", key = "").isAxonOpsEnabled()).isFalse()
    }

    @Test
    fun `isAxonOpsEnabled returns false when the org is whitespace rather than empty`() {
        assertThat(user(org = "   ", key = "axon-key").isAxonOpsEnabled()).isFalse()
    }

    @Test
    fun `isAxonOpsEnabled returns false when the key is whitespace rather than empty`() {
        // isNotEmpty() would accept "   "; isNotBlank() rejects it. Without this case the whole
        // class stays green under a weakened key operand.
        assertThat(user(org = "acme", key = "   ").isAxonOpsEnabled()).isFalse()
    }
}
