package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module

class ShowIamPoliciesTest : BaseKoinTest() {
    private lateinit var outputHandler: BufferedOutputHandler

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { AWSResourceSetupService(get<AWS>(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    @Test
    fun `execute outputs all policies when no filter`() {
        val command = ShowIamPolicies()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        // STS mock returns account ID "123456789012"
        assertThat(output).contains("123456789012")
    }

    @Test
    fun `execute filters policies by name`() {
        val command = ShowIamPolicies()
        command.policyName = "ec2"
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).containsIgnoringCase("ec2")
    }

    @Test
    fun `execute outputs no policies message for non-matching filter`() {
        val command = ShowIamPolicies()
        command.policyName = "nonexistentpolicyxyz"
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("No policies found matching")
    }
}
