package com.rustyrazorblade.easydblab.providers.aws

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient

/**
 * Factory interface for creating AWS clients.
 * Allows injection of mock clients during testing.
 */
interface AWSClientFactory {
    /**
     * Creates an AWS service wrapper with the given credentials.
     *
     * @param accessKey AWS access key
     * @param secret AWS secret key
     * @param region AWS region
     * @return AWS service wrapper
     */
    fun createAWSClient(
        accessKey: String,
        secret: String,
        region: Region,
    ): AWS

    /**
     * Creates an AWS service wrapper using an AWS profile from ~/.aws/credentials.
     *
     * @param profileName AWS profile name
     * @param region AWS region
     * @return AWS service wrapper
     */
    fun createAWSClientWithProfile(
        profileName: String,
        region: Region,
    ): AWS

    /**
     * Creates an EC2 client with the given credentials.
     *
     * @param accessKey AWS access key
     * @param secret AWS secret key
     * @param region AWS region
     * @return EC2 client
     */
    fun createEc2Client(
        accessKey: String,
        secret: String,
        region: Region,
    ): Ec2Client

    /**
     * Creates an EC2 client using an AWS profile from ~/.aws/credentials.
     *
     * @param profileName AWS profile name
     * @param region AWS region
     * @return EC2 client
     */
    fun createEc2ClientWithProfile(
        profileName: String,
        region: Region,
    ): Ec2Client
}

/**
 * Default implementation that creates real AWS SDK clients.
 *
 * AWS SDK instrumentation is handled automatically by the OpenTelemetry Java agent.
 */
class DefaultAWSClientFactory : AWSClientFactory {
    override fun createAWSClient(
        accessKey: String,
        secret: String,
        region: Region,
    ): AWS {
        val credentialsProvider =
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secret),
            )
        return createAWSWithProvider(credentialsProvider, region)
    }

    override fun createAWSClientWithProfile(
        profileName: String,
        region: Region,
    ): AWS {
        val credentialsProvider = ProfileCredentialsProvider.create(profileName)
        return createAWSWithProvider(credentialsProvider, region)
    }

    override fun createEc2Client(
        accessKey: String,
        secret: String,
        region: Region,
    ): Ec2Client {
        val credentialsProvider =
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secret),
            )
        return createEc2ClientWithProvider(credentialsProvider, region)
    }

    override fun createEc2ClientWithProfile(
        profileName: String,
        region: Region,
    ): Ec2Client {
        val credentialsProvider = ProfileCredentialsProvider.create(profileName)
        return createEc2ClientWithProvider(credentialsProvider, region)
    }

    private fun createAWSWithProvider(
        credentialsProvider: AwsCredentialsProvider,
        region: Region,
    ): AWS {
        val overrideConfig = ClientOverrideConfiguration.builder().build()

        val iamClient =
            IamClient
                .builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .overrideConfiguration(overrideConfig)
                .build()

        val s3Client =
            S3Client
                .builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .overrideConfiguration(overrideConfig)
                .build()

        val stsClient =
            StsClient
                .builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .overrideConfiguration(overrideConfig)
                .build()

        return AWS(iamClient, s3Client, stsClient)
    }

    private fun createEc2ClientWithProvider(
        credentialsProvider: AwsCredentialsProvider,
        region: Region,
    ): Ec2Client {
        val overrideConfig = ClientOverrideConfiguration.builder().build()
        return Ec2Client
            .builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .overrideConfiguration(overrideConfig)
            .build()
    }
}
