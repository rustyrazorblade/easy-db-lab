package com.rustyrazorblade.easydblab.commands.converters

import com.rustyrazorblade.easydblab.configuration.CniMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import picocli.CommandLine.TypeConversionException

class PicoCniModeConverterTest {
    private val converter = PicoCniModeConverter()

    @Test
    fun `converts cilium to CniMode Cilium`() {
        assertThat(converter.convert("cilium")).isEqualTo(CniMode.Cilium)
    }

    @Test
    fun `converts flannel to CniMode Flannel`() {
        assertThat(converter.convert("flannel")).isEqualTo(CniMode.Flannel)
    }

    @Test
    fun `handles uppercase input`() {
        assertThat(converter.convert("CILIUM")).isEqualTo(CniMode.Cilium)
        assertThat(converter.convert("FLANNEL")).isEqualTo(CniMode.Flannel)
    }

    @Test
    fun `handles mixed case input`() {
        assertThat(converter.convert("Cilium")).isEqualTo(CniMode.Cilium)
        assertThat(converter.convert("FlAnNeL")).isEqualTo(CniMode.Flannel)
    }

    @Test
    fun `trims whitespace from input`() {
        assertThat(converter.convert("  cilium  ")).isEqualTo(CniMode.Cilium)
        assertThat(converter.convert("\tflannel\t")).isEqualTo(CniMode.Flannel)
    }

    @Test
    fun `throws TypeConversionException for invalid cni`() {
        assertThatThrownBy { converter.convert("foo") }
            .isInstanceOf(TypeConversionException::class.java)
            .hasMessageContaining("Invalid CNI: foo")
            .hasMessageContaining("cilium or flannel")
    }

    @Test
    fun `throws TypeConversionException for empty input`() {
        assertThatThrownBy { converter.convert("") }
            .isInstanceOf(TypeConversionException::class.java)
            .hasMessageContaining("Invalid CNI")
            .hasMessageContaining("cilium or flannel")
    }

    @Test
    fun `throws TypeConversionException for whitespace-only input`() {
        assertThatThrownBy { converter.convert("   ") }
            .isInstanceOf(TypeConversionException::class.java)
            .hasMessageContaining("Invalid CNI")
            .hasMessageContaining("cilium or flannel")
    }
}
