package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine

class ServerHelpTest : BaseKoinTest() {
    @Test
    fun `server help names the status and API docs endpoints and no MCP server`() {
        val usage = CommandLine(Server()).usageMessage

        assertThat(usage).contains("/status").contains("/swagger")
        assertThat(usage).doesNotContainIgnoringCase("mcp").doesNotContain("/sse")
    }
}
