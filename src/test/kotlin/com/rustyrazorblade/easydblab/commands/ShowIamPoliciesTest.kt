package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class ShowIamPoliciesTest : BaseKoinTest() {
    private lateinit var outputHandler: BufferedOutputHandler
    private val stdout = ByteArrayOutputStream()
    private val originalOut = System.out

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { AWSResourceSetupService(get<AWS>(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
        System.setOut(PrintStream(stdout))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
        stdout.reset()
    }

    @Test
    fun `execute outputs all policies when no filter`() {
        val command = ShowIamPolicies()
        command.execute()

        // STS mock returns account ID "123456789012"
        assertThat(stdout.toString()).contains("123456789012")
    }

    @Test
    fun `execute filters policies by name`() {
        val command = ShowIamPolicies()
        command.policyName = "ec2"
        command.execute()

        assertThat(stdout.toString()).containsIgnoringCase("ec2")
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
