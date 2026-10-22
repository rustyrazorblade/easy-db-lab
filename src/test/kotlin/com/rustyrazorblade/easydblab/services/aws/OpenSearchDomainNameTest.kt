package com.rustyrazorblade.easydblab.services.aws

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class OpenSearchDomainNameTest {
    @Test
    fun `an underscore becomes a hyphen`() {
        assertThat(OpenSearchDomainName.of("unthrottled_cursor")).isEqualTo("unthrottled-cursor-os")
    }

    @Test
    fun `a short name keeps every character`() {
        assertThat(OpenSearchDomainName.of("lab")).isEqualTo("lab-os")
    }

    /** 40 characters, the longest cluster name: the base is cut, never the suffix. */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "unthrottled_cursor_with_a_very_long_name",
            "abcdefghijabcdefghijabcdefghijabcdefghij",
            "a_b_c_d_e_f_g_h_i_j_k_l_m_n_o_p_q_r_s_t_",
        ],
    )
    fun `a long name fits in 28 characters and keeps its suffix`(clusterName: String) {
        val domain = OpenSearchDomainName.of(clusterName)

        assertThat(domain).hasSizeLessThanOrEqualTo(28).endsWith("-os")
        assertThat(domain.removeSuffix("-os")).doesNotEndWith("-")
    }

    /** The cut lands right after a `-`: the base is trimmed, so there is no `--os`. */
    @Test
    fun `a base cut on a hyphen is trimmed before the suffix`() {
        assertThat(OpenSearchDomainName.of("abcdefghijabcdefghijabcd_xyz")).isEqualTo("abcdefghijabcdefghijabcd-os")
    }
}

/** A 40-character cluster name with `_`, the longest the rule allows. */
internal const val LONG_UNDERSCORE_NAME = "unthrottled_cursor_with_a_very_long_name"

/** The domain name both `opensearch start` and cluster provisioning must derive from [LONG_UNDERSCORE_NAME]. */
internal const val LONG_UNDERSCORE_DOMAIN = "unthrottled-cursor-with-a-os"
