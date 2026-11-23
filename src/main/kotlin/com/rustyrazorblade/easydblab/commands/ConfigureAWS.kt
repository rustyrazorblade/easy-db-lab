package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Configure AWS infrastructure (IAM roles, S3 bucket) for easy-db-lab.
 */
@RequireProfileSetup
@Command(
    name = "configure-aws",
    description = ["Configure AWS infrastructure (IAM roles, S3 bucket) for easy-db-lab"],
)
class ConfigureAWS : PicoBaseCommand() {
    private val awsResourceSetupService: AWSResourceSetupService by inject()

    override fun execute() {
        awsResourceSetupService.ensureAWSResources()
        eventBus.emit(Event.Setup.AwsConfigured)
    }
}
