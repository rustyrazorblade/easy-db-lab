package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireDocker
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.mixins.BuildArgsMixin
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.containers.Packer
import com.rustyrazorblade.easydblab.services.ExternalIpService
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
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
class BuildBaseImage : PicoBaseCommand() {
    @Mixin
    var buildArgs = BuildArgsMixin()

    private val awsInfrastructure: AwsInfrastructureService by inject()
    private val awsResourceSetupService: AWSResourceSetupService by inject()
    private val s3BucketService: AwsS3BucketService by inject()
    private val externalIpService: ExternalIpService by inject()
    private val userConfig: User by inject()

    override fun execute() {
        // Populate buildArgs from userConfig if not overridden by command-line args
        if (buildArgs.region.isBlank()) {
            buildArgs.region = userConfig.region
        }

        // Ensure AWS infrastructure (IAM roles, S3) exists first
        awsResourceSetupService.ensureAWSResources(userConfig)
        // Ensure the account bucket exists so the S3 build cache is active (migrates old profiles)
        s3BucketService.ensureAccountBucket(userConfig)

        // Ensure Packer VPC infrastructure exists before building, scoping SSH to this machine's IP
        awsInfrastructure.ensurePackerInfrastructure(Constants.Network.SSH_PORT, externalIpService.getExternalCidr())

        val packer = Packer(context, "base")
        packer.build("base.pkr.hcl", buildArgs)
    }
}
