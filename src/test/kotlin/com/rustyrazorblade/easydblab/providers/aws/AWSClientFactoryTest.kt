package com.rustyrazorblade.easydblab.providers.aws

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Region validation that profile setup relies on: a blank or misspelt region is refused before any
 * client is built, and a region the SDK knows is accepted.
 */
class AWSClientFactoryTest {
    @Test
    fun `a blank region is not known`() {
        assertThat(AWSClientFactory.isKnownRegion("")).isFalse()
    }

    @Test
    fun `a misspelt region is not known`() {
        assertThat(AWSClientFactory.isKnownRegion("us-wset-2")).isFalse()
    }

    @Test
    fun `a named region is known`() {
        assertThat(AWSClientFactory.isKnownRegion("us-west-2")).isTrue()
    }
}
