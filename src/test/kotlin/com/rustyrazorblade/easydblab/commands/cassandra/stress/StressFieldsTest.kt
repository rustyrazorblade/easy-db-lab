package com.rustyrazorblade.easydblab.commands.cassandra.stress

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.captureStdout
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.StressJobService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class StressFieldsTest : BaseKoinTest() {
    private val mockStressJobService: StressJobService = mock()

    override fun additionalTestModules(): List<Module> = listOf(module { single<StressJobService> { mockStressJobService } })

    private lateinit var outputHandler: BufferedOutputHandler

    @BeforeEach
    fun setup() {
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    @Test
    fun `the field generators print as plain text, not as an event`() {
        whenever(mockStressJobService.runCommand(any(), eq(listOf("fields")))).thenReturn(Result.success("random\nbook"))

        val printed = captureStdout { StressFields().execute() }

        assertThat(printed).isEqualTo("random\nbook\n")
        assertThat(outputHandler.messages.joinToString("\n")).doesNotContain("book")
    }

    @Test
    fun `an empty result prints nothing`() {
        whenever(mockStressJobService.runCommand(any(), any())).thenReturn(Result.success(""))

        assertThat(captureStdout { StressFields().execute() }).isEmpty()
    }
}
