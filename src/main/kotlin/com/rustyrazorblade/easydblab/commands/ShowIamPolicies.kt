package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters

/**
 * Display IAM policies with account ID populated.
 */
@RequireProfileSetup
@Command(
    name = "show-iam-policies",
    aliases = ["sip"],
    description = ["Display IAM policies with account ID populated"],
)
class ShowIamPolicies : PicoBaseCommand() {
    private val awsResourceSetup: AWSResourceSetupService by inject()

    @Parameters(
        description = ["Policy name filter (optional): ec2, iam, emr"],
        arity = "0..1",
        defaultValue = "",
    )
    var policyName: String = ""

    @Suppress("TooGenericExceptionCaught")
    override fun execute() {
        // Get account ID - REQUIRED, will fail if not configured
        val accountId =
            try {
                awsResourceSetup.getAccountId()
            } catch (e: Exception) {
                outputHandler.handleError("Failed to get AWS account ID. Please run 'easy-db-lab init' to set up credentials.")
                throw e
            }

        val policies = User.getRequiredIAMPolicies(accountId)

        // Filter by name if provided
        val filtered =
            if (policyName.isNotBlank()) {
                policies.filter { it.name.contains(policyName, ignoreCase = true) }
            } else {
                policies
            }

        if (filtered.isEmpty()) {
            outputHandler.handleMessage("No policies found matching: $policyName")
            return
        }

        filtered.forEach { policy ->
            if (policyName.isBlank()) {
                // Show all policies with headers for readability
                outputHandler.handleMessage("\n=== ${policy.name} ===\n")
            }
            outputHandler.handleMessage(policy.body)
        }
    }
}
