package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
                single { AWSResourceSetupService(get<AWS>(), get(), get()) }
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

    @Test
    fun `the ec2 policy lets the operator open SSM sessions only to the lab's own instances`() {
        val command = ShowIamPolicies()
        command.policyName = "ec2"
        command.execute()

        val statements =
            Json
                .parseToJsonElement(stdout.toString())
                .jsonObject
                .getValue("Statement")
                .jsonArray
                .map { it.jsonObject }

        val startSession = statements.filter { it.allows("ssm:StartSession") }
        val onInstances = startSession.filter { "arn:aws:ec2:*:123456789012:instance/*" in stringsOf(it.getValue("Resource")) }
        // Only instances this tool tagged: a session is a shell on the instance, so an untagged
        // instance in the same account must stay out of reach.
        assertThat(onInstances).hasSize(1).allSatisfy { statement ->
            val condition = statement["Condition"]?.jsonObject?.get("StringEquals")?.jsonObject
            assertThat(condition?.get("ssm:resourceTag/easy_cass_lab")?.jsonPrimitive?.content).isEqualTo("1")
        }
        assertThat(startSession.filterNot { it in onInstances }.flatMap { stringsOf(it.getValue("Resource")) })
            .containsExactlyInAnyOrder(
                "arn:aws:ssm:*::document/AWS-StartSSHSession",
                "arn:aws:ssm:*::document/AWS-StartPortForwardingSession",
            )

        // Session IDs start with the user or role session name, never with aws:userid, so the
        // caller's own sessions are matched on the session-id tag Session Manager puts on each
        // session, which holds exactly aws:userid for IAM users and assumed roles alike.
        listOf("ssm:TerminateSession", "ssm:ResumeSession").forEach { action ->
            val granting = statements.filter { it.allows(action) }
            assertThat(granting).hasSize(1).allSatisfy { statement ->
                assertThat(stringsOf(statement.getValue("Resource"))).containsExactly("arn:aws:ssm:*:123456789012:session/*")
                val condition = statement["Condition"]?.jsonObject?.get("StringEquals")?.jsonObject
                assertThat(condition?.get("ssm:resourceTag/aws:ssmmessages:session-id")?.jsonPrimitive?.content)
                    .isEqualTo("\${aws:userid}")
            }
        }
        // ssmmessages takes no resource ARN at all, so a session ARN would match nothing.
        assertThat(resourcesGranting(statements, "ssmmessages:OpenDataChannel")).containsExactly("*")
    }

    private fun JsonObject.allows(action: String): Boolean =
        getValue("Effect").jsonPrimitive.content == "Allow" && action in stringsOf(getValue("Action"))

    /** Every resource an Allow statement grants [action] on; `Action` and `Resource` may each be a string or a list. */
    private fun resourcesGranting(
        statements: List<JsonObject>,
        action: String,
    ): List<String> =
        statements
            .filter { it.allows(action) }
            .flatMap { stringsOf(it.getValue("Resource")) }

    private fun stringsOf(element: JsonElement): List<String> =
        when (element) {
            is JsonArray -> element.map { it.jsonPrimitive.content }
            is JsonPrimitive -> listOf(element.content)
            else -> emptyList()
        }
}
