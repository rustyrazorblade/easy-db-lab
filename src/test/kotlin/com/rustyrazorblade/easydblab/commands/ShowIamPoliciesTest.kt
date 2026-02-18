package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ShowIamPoliciesTest : BaseKoinTest() {
    private lateinit var outputHandler: BufferedOutputHandler

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
