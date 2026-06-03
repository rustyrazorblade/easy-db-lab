package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class VersionTest : BaseKoinTest() {
    private val stdout = ByteArrayOutputStream()
    private val originalOut = System.out

    @BeforeEach
    fun captureStdout() {
        System.setOut(PrintStream(stdout))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
    }

    @Test
    fun `execute outputs version string`() {
        Version().execute()
        assertThat(stdout.toString().trim()).isNotBlank()
    }
}
