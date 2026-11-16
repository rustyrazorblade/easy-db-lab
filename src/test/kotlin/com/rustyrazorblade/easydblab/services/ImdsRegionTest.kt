package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class ImdsRegionTest {
    @ParameterizedTest
    @CsvSource("us-west-2,us-west-2", "'eu-central-1\n',eu-central-1", "ap-southeast-2,ap-southeast-2", "us-gov-west-1,us-gov-west-1")
    fun `a region name is returned trimmed`(
        output: String,
        expected: String,
    ) {
        assertThat(ImdsRegion.parse(output)).isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "", "  \n", "401 Unauthorized", "us-west-2a"])
    fun `anything that is not a region fails instead of becoming a datacenter`(output: String) {
        assertThatThrownBy { ImdsRegion.parse(output) }.isInstanceOf(CommandFailedException::class.java)
    }
}
