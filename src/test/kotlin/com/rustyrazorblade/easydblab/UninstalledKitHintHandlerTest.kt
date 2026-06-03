package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import picocli.CommandLine
import picocli.CommandLine.Command
import java.io.PrintWriter
import java.io.StringWriter

class UninstalledKitHintHandlerTest {
    private lateinit var resolver: InstallTemplateResolver
    private lateinit var delegate: CommandLine.IParameterExceptionHandler
    private lateinit var handler: UninstalledKitHintHandler
    private lateinit var errWriter: StringWriter
    private lateinit var cl: CommandLine

    @Command(name = "test-root")
    class MinimalCommand : Runnable {
        override fun run() = Unit
    }

    @BeforeEach
    fun setup() {
        resolver = mock()
        delegate = mock()
        handler = UninstalledKitHintHandler(resolver = resolver, delegate = delegate)

        errWriter = StringWriter()
        cl = CommandLine(MinimalCommand()).also { it.err = PrintWriter(errWriter) }
    }

    @Test
    fun `prints not-installed hint and returns 2 when token matches available template`() {
        whenever(resolver.listAvailableTemplates()).thenReturn(listOf("presto", "clickhouse"))

        val ex = CommandLine.UnmatchedArgumentException(cl, listOf("presto"))
        val result = handler.handleParseException(ex, arrayOf("presto"))

        assertThat(result).isEqualTo(2)
        assertThat(errWriter.toString()).contains("presto is not installed")
        assertThat(errWriter.toString()).contains("easy-db-lab kit install presto")
        verify(delegate, never()).handleParseException(ex, arrayOf("presto"))
    }

    @Test
    fun `delegates to default handler when token does not match any available template`() {
        whenever(resolver.listAvailableTemplates()).thenReturn(listOf("presto", "clickhouse"))
        whenever(delegate.handleParseException(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(2)

        val ex = CommandLine.UnmatchedArgumentException(cl, listOf("unknownxyz"))
        handler.handleParseException(ex, arrayOf("unknownxyz"))

        assertThat(errWriter.toString()).doesNotContain("is not installed")
        verify(delegate).handleParseException(ex, arrayOf("unknownxyz"))
    }
}
