package com.rustyrazorblade.easydblab.providers.aws

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Region validation that profile setup relies on: a blank or misspelt region is rejected before any
 * client is built, and every region the SDK knows is accepted.
 */
class AWSClientFactoryTest {
    @Test
    fun `a blank region is rejected`() {
        assertThatThrownBy { AWSClientFactory.requireValidRegion("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a misspelt region is rejected and named`() {
        assertThatThrownBy { AWSClientFactory.requireValidRegion("us-wset-2") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("us-wset-2")
    }

    @Test
    fun `a named region is accepted`() {
        assertThatCode { AWSClientFactory.requireValidRegion("us-west-2") }.doesNotThrowAnyException()
    }
}
