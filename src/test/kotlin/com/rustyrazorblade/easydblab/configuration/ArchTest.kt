package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Tests for [Arch.fromEc2], which maps the EC2 `SupportedArchitectures` values reported by
 * `DescribeInstanceTypes` onto the project's [Arch] enum. The mapping must be exact: an instance
 * type whose supported architectures do not resolve to exactly one [Arch] is an error, because the
 * cluster cannot pick an AMI for an ambiguous or unknown architecture.
 */
class ArchTest {
    @ParameterizedTest
    @CsvSource("amd64,AMD64", "x86_64,AMD64", "aarch64,ARM64", "arm64,ARM64")
    fun `maps the JVM's os arch onto an architecture`(
        osArch: String,
        expected: Arch,
    ) {
        assertThat(Arch.fromOsArch(osArch)).isEqualTo(expected)
    }

    @Test
    fun `rejects an os arch it cannot run, naming it`() {
        assertThatThrownBy { Arch.fromOsArch("ppc64le") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ppc64le")
    }

    @Test
    fun `maps x86_64 to AMD64`() {
        assertThat(Arch.fromEc2(listOf("x86_64"))).isEqualTo(Arch.AMD64)
    }

    @Test
    fun `maps arm64 to ARM64`() {
        assertThat(Arch.fromEc2(listOf("arm64"))).isEqualTo(Arch.ARM64)
    }

    @Test
    fun `rejects i386 naming the value`() {
        assertThatThrownBy { Arch.fromEc2(listOf("i386")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("i386")
    }

    @Test
    fun `rejects an empty architecture list`() {
        assertThatThrownBy { Arch.fromEc2(emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects an unrecognized architecture naming the value`() {
        assertThatThrownBy { Arch.fromEc2(listOf("sparc")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("sparc")
    }

    @Test
    fun `rejects a list that maps to more than one architecture`() {
        assertThatThrownBy { Arch.fromEc2(listOf("x86_64", "arm64")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
