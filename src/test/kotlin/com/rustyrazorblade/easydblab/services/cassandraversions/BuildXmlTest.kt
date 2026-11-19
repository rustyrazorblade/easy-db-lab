package com.rustyrazorblade.easydblab.services.cassandraversions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** `java.supported` in a Cassandra `build.xml` is a comma list; 4.x trees do not declare it. */
class BuildXmlTest {
    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(
        delimiter = '|',
        value = [
            """<property name="java.supported" value="11,17,21" />|11 17 21""",
            """<property name="java.supported" value="11, 17" />|11 17""",
            """<property  name="java.supported"   value=" 17 "/>|17""",
            """<property name="java.default" value="11" />|''""",
            """<project/>|''""",
            """<property name="java.supported" value="" />|''""",
        ],
    )
    fun `java supported is read from the property`(
        xml: String,
        expected: String,
    ) {
        val want = expected.split(" ").filter { it.isNotEmpty() }

        assertThat(BuildXml.javaSupported(xml)).isEqualTo(want)
    }
}
