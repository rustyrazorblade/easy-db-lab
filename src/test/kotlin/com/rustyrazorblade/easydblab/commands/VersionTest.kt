package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VersionTest : BaseKoinTest() {
    @Test
    fun `execute outputs version string`() {
        val outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        val command = Version()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).isNotBlank()
    }
}
