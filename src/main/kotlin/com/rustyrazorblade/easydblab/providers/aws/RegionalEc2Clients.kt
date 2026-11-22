package com.rustyrazorblade.easydblab.providers.aws

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client

/**
 * Builds EC2 clients for a region named at call time. An AMI build runs in its own region
 * (`--region`), which can differ from the profile's, so the profile-region [Ec2Client] in
 * `awsClientsModule` cannot set up its network or find its builders; this keeps the client
 * construction in the clients module, so `services/aws` never builds an SDK client.
 *
 * Each call builds a new client; the caller closes it.
 */
class RegionalEc2Clients(
    private val credentials: AwsCredentialsProvider,
) {
    /** A new EC2 client for [region], signed with the profile's credentials. */
    fun forRegion(region: String): Ec2Client =
        Ec2Client
            .builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .overrideConfiguration(ClientOverrideConfiguration.builder().build())
            .build()
}
