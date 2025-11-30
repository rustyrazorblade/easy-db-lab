package com.rustyrazorblade.easycasslab.commands

import com.rustyrazorblade.easycasslab.Constants
import com.rustyrazorblade.easycasslab.Context
import com.rustyrazorblade.easycasslab.annotations.RequireDocker
import com.rustyrazorblade.easycasslab.annotations.RequireProfileSetup
import com.rustyrazorblade.easycasslab.commands.mixins.BuildArgsMixin
import com.rustyrazorblade.easycasslab.configuration.User
import com.rustyrazorblade.easycasslab.containers.Packer
import com.rustyrazorblade.easycasslab.providers.aws.AwsInfrastructureService
import com.rustyrazorblade.easycasslab.services.AWSResourceSetupService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

/**
 * Build the base AMI image.
 */
@RequireDocker
@RequireProfileSetup
@Command(
    name = "build-base",
    description = ["Build the base image"],
)
class BuildBaseImage(
    context: Context,
) : PicoBaseCommand(context) {
    @Mixin
    var buildArgs = BuildArgsMixin()

    private val awsInfrastructure: AwsInfrastructureService by inject()
    private val awsResourceSetupService: AWSResourceSetupService by inject()
    private val userConfig: User by inject()

    override fun execute() {
        // Populate buildArgs from userConfig if not overridden by command-line args
        if (buildArgs.region.isBlank()) {
            buildArgs.region = userConfig.region
        }

        // Ensure AWS infrastructure (IAM roles, S3) exists first
        awsResourceSetupService.ensureAWSResources(userConfig)

        // Ensure Packer VPC infrastructure exists before building
        awsInfrastructure.ensurePackerInfrastructure(Constants.Network.SSH_PORT)

        val packer = Packer(context, "base")
        packer.build("base.pkr.hcl", buildArgs)
    }
}
