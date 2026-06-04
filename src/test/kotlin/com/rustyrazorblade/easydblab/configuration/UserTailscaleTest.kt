package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UserTailscaleTest {
    private fun user(
        clientId: String = "",
        clientSecret: String = "",
    ) = User(
        email = "test@example.com",
        region = "us-east-1",
        keyName = "test-key",
        awsProfile = "default",
        awsAccessKey = "",
        awsSecret = "",
        tailscaleClientId = clientId,
        tailscaleClientSecret = clientSecret,
    )

    @Test
    fun `isTailscaleEnabled returns true when both credentials are non-blank`() {
        assertThat(user(clientId = "client-id", clientSecret = "client-secret").isTailscaleEnabled()).isTrue()
    }

    @Test
    fun `isTailscaleEnabled returns false when clientId is blank`() {
        assertThat(user(clientId = "", clientSecret = "client-secret").isTailscaleEnabled()).isFalse()
    }

    @Test
    fun `isTailscaleEnabled returns false when clientSecret is blank`() {
        assertThat(user(clientId = "client-id", clientSecret = "").isTailscaleEnabled()).isFalse()
    }

    @Test
    fun `isTailscaleEnabled returns false when both credentials are blank`() {
        assertThat(user().isTailscaleEnabled()).isFalse()
    }
}
