package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ExperimentKeyTest {
    @Test
    fun `the picker value is the tenant and the name joined by a slash`() {
        assertThat(ExperimentKey("acme", "e1").pickerValue).isEqualTo("acme/e1")
    }

    /** Names are unique within a tenant only, so the same name in two tenants is two experiments. */
    @Test
    fun `the same name in two tenants is two experiments`() {
        val acme = ExperimentKey("acme", "e1")
        val default = ExperimentKey("default", "e1")

        assertThat(acme).isNotEqualTo(default)
        assertThat(acme.pickerValue).isNotEqualTo(default.pickerValue)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "2026-10-13-ab",
            "e",
            "9",
            "unthrottled_cursor-0f3c",
            // 80 characters: the longest allowed.
            "abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghij",
        ],
    )
    fun `a name inside the rule is accepted`(name: String) {
        assertThat(ExperimentKey("default", name).name).isEqualTo(name)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "AB-test",
            "-ab",
            "_ab",
            "a/b",
            "a b",
            "",
            // 81 characters: one past the limit.
            "abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijk",
        ],
    )
    fun `a name that breaks the rule is refused, stating the rule`(name: String) {
        assertThatThrownBy { ExperimentKey("default", name) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("^[a-z0-9][a-z0-9_-]{0,79}$")
    }

    @ParameterizedTest
    @ValueSource(strings = ["Acme", "1acme", "a/b", ""])
    fun `a tenant that breaks the tenant rule is refused, stating the rule`(tenant: String) {
        assertThatThrownBy { ExperimentKey(tenant, "e1") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("^[a-z][a-z0-9_-]{0,62}$")
    }

    @Test
    fun `the reserved tenant is refused`() {
        assertThatThrownBy { ExperimentKey("index", "e1") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'index' is reserved")
    }
}
