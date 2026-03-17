package com.rustyrazorblade.easydblab.commands.converters

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import picocli.CommandLine.TypeConversionException

class PicoAZConverterTest {
    private val converter = PicoAZConverter()

    @Test
    fun `converts single letter suffix`() {
        val result = converter.convert("a")
        assertThat(result).containsExactly("a")
    }

    @Test
    fun `converts concatenated letters to list`() {
        val result = converter.convert("abc")
        assertThat(result).containsExactly("a", "b", "c")
    }

    @Test
    fun `converts comma-separated letters to list`() {
        val result = converter.convert("a,b,c")
        assertThat(result).containsExactly("a", "b", "c")
    }

    @Test
    fun `converts space-separated letters to list`() {
        val result = converter.convert("a b c")
        assertThat(result).containsExactly("a", "b", "c")
    }

    @Test
    fun `handles mixed separators`() {
        val result = converter.convert("a b , c")
        assertThat(result).containsExactly("a", "b", "c")
    }

    @Test
    fun `preserves order of availability zones`() {
        val result = converter.convert("cba")
        assertThat(result).containsExactly("c", "b", "a")
    }

    @Test
    fun `returns empty list for empty input`() {
        val result = converter.convert("")
        assertThat(result).isEmpty()
    }

    @Test
    fun `throws on token with no valid letters`() {
        assertThatThrownBy { converter.convert("123!@#") }
            .isInstanceOf(TypeConversionException::class.java)
    }

    // Full AZ name support

    @Test
    fun `converts full AZ name to letter suffix`() {
        val result = converter.convert("us-east-1c")
        assertThat(result).containsExactly("c")
    }

    @Test
    fun `converts multiple full AZ names comma-separated`() {
        val result = converter.convert("us-east-1c,us-east-1d,us-east-1e")
        assertThat(result).containsExactly("c", "d", "e")
    }

    @Test
    fun `converts multiple full AZ names space-separated`() {
        val result = converter.convert("us-east-1c us-east-1d us-east-1e")
        assertThat(result).containsExactly("c", "d", "e")
    }

    @Test
    fun `converts eu-west region AZ name`() {
        val result = converter.convert("eu-west-1a")
        assertThat(result).containsExactly("a")
    }

    @Test
    fun `converts ap-southeast region AZ name`() {
        val result = converter.convert("ap-southeast-1b")
        assertThat(result).containsExactly("b")
    }

    @Test
    fun `converts mixed full AZ names and letter suffixes`() {
        val result = converter.convert("us-east-1c d")
        assertThat(result).containsExactly("c", "d")
    }
}
