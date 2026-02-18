package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ConfigureAWSTest : BaseKoinTest() {
    private lateinit var mockAWSResourceSetupService: AWSResourceSetupService
    private lateinit var outputHandler: BufferedOutputHandler

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<AWSResourceSetupService> { mockAWSResourceSetupService }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockAWSResourceSetupService = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockAWSResourceSetupService.ensureAWSResources(any())).then { }
    }

    @Test
    fun `execute delegates to AWSResourceSetupService`() {
        val command = ConfigureAWS()
        command.execute()

        verify(mockAWSResourceSetupService).ensureAWSResources(any())
    }

    @Test
    fun `execute outputs success message`() {
        val command = ConfigureAWS()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("configured successfully")
    }
}
