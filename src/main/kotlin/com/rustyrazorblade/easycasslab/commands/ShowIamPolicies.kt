package com.rustyrazorblade.easycasslab.commands

import com.rustyrazorblade.easycasslab.Context
import com.rustyrazorblade.easycasslab.annotations.RequireProfileSetup
import com.rustyrazorblade.easycasslab.configuration.User
import com.rustyrazorblade.easycasslab.providers.aws.AWS
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
class ShowIamPolicies(
    context: Context,
) : PicoBaseCommand(context) {
    private val aws: AWS by inject()

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
                aws.getAccountId()
            } catch (e: Exception) {
                outputHandler.handleError("Failed to get AWS account ID. Please run 'easy-cass-lab init' to set up credentials.")
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
