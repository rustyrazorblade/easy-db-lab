package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresCapability
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Configure AWS infrastructure (IAM roles, S3 bucket) for easy-db-lab.
 */
@RequireProfileSetup
@RequiresCapability(NodeCapability.AWS_ACCOUNT)
@Command(
    name = "configure-aws",
    description = ["Configure AWS infrastructure (IAM roles, S3 bucket) for easy-db-lab"],
)
class ConfigureAWS : PicoBaseCommand() {
    private val awsResourceSetupService: AWSResourceSetupService by inject()
    private val userConfig: User by inject()

    override fun execute() {
        awsResourceSetupService.ensureAWSResources(userConfig)
        eventBus.emit(Event.Setup.AwsConfigured)
    }
}
