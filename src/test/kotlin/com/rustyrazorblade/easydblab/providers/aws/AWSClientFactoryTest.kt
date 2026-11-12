package com.rustyrazorblade.easydblab.providers.aws

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Region validation that profile setup relies on: a blank region is rejected before any client is
 * built, exactly as when the caller held an SDK `Region`. Typos are not detected, by decision.
 */
class AWSClientFactoryTest {
    @Test
    fun `a blank region is rejected`() {
        assertThatThrownBy { AWSClientFactory.requireValidRegion("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a named region is accepted`() {
        assertThatCode { AWSClientFactory.requireValidRegion("us-west-2") }.doesNotThrowAnyException()
    }
}
