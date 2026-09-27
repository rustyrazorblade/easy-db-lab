package com.rustyrazorblade.easydblab.providers.aws

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ecs.EcsClient

/**
 * AWS clients for a region named at call time. The account compactor runs in the account bucket's
 * region, which can differ from the profile's, and `down` counts clusters in every enabled region;
 * the profile-region clients in [awsModule] cannot reach either.
 *
 * Each call builds a new client; callers close it.
 */
interface RegionalClients {
    fun ec2(region: String): Ec2Client

    fun ecs(region: String): EcsClient

    fun logs(region: String): CloudWatchLogsClient
}

/**
 * [RegionalClients] with the profile's credentials.
 */
class DefaultRegionalClients(
    private val credentials: AwsCredentialsProvider,
) : RegionalClients {
    override fun ec2(region: String): Ec2Client =
        Ec2Client
            .builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .build()

    override fun ecs(region: String): EcsClient =
        EcsClient
            .builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .build()

    override fun logs(region: String): CloudWatchLogsClient =
        CloudWatchLogsClient
            .builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .build()
}
