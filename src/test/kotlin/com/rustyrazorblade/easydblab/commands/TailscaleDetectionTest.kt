package com.rustyrazorblade.easydblab.commands

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class TailscaleDetectionTest {
    @Test
    fun `parseTailscaleOutput returns true when BackendState is Running`() {
        val json = """{"BackendState":"Running","Version":"1.60.0","TailscaleIPs":["100.x.x.x"]}"""
        assertThat(parseTailscaleOutput(json)).isTrue()
    }

    @Test
    fun `parseTailscaleOutput returns false when BackendState is Stopped`() {
        val json = """{"BackendState":"Stopped"}"""
        assertThat(parseTailscaleOutput(json)).isFalse()
    }

    @Test
    fun `parseTailscaleOutput returns false when BackendState is NeedsLogin`() {
        val json = """{"BackendState":"NeedsLogin"}"""
        assertThat(parseTailscaleOutput(json)).isFalse()
    }

    @Test
    fun `parseTailscaleOutput returns false for empty string`() {
        assertThat(parseTailscaleOutput("")).isFalse()
    }

    @Test
    fun `parseTailscaleOutput returns false for JSON with spaces around colon`() {
        // The implementation uses exact string matching — Tailscale CLI always emits
        // compact JSON, so "BackendState" : "Running" (with spaces) is not matched.
        val json = """{"BackendState" : "Running"}"""
        assertThat(parseTailscaleOutput(json)).isFalse()
    }

    @Test
    fun `detectLocalTailscale does not throw when tailscale binary is absent`() {
        // On CI where tailscale is not installed this exercises the IOException path.
        // On dev machines with active Tailscale it simply returns true — both are valid.
        assertThatCode { detectLocalTailscale() }.doesNotThrowAnyException()
    }
}
