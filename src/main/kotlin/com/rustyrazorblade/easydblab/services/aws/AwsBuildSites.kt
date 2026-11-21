package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.containers.BuildSite
import com.rustyrazorblade.easydblab.containers.BuildSites
import com.rustyrazorblade.easydblab.containers.BuilderInstances
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.messageOrString
import com.rustyrazorblade.easydblab.providers.aws.Ec2BuilderInstances
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.providers.aws.withAwsRetry
import com.rustyrazorblade.easydblab.providers.ssh.OpenSshPublicKey
import com.rustyrazorblade.easydblab.runCatchingException
import com.rustyrazorblade.easydblab.runCleaningUpOnFailure
import com.rustyrazorblade.easydblab.services.ExternalIpService
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DeleteKeyPairRequest
import software.amazon.awssdk.services.ec2.model.ImportKeyPairRequest
import software.amazon.awssdk.services.ec2.model.ResourceType
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.ec2.model.TagSpecification
import java.io.File

/**
 * The AWS adapter of [BuildSites]: every AMI build runs on EC2 clients for its own region.
 *
 * It ensures the packer VPC, subnet and security group exist in that region (with SSH open to this
 * machine's address only), and binds the build's instances to [Ec2BuilderInstances]. The profile's
 * key pair exists only in the profile region, so a build elsewhere imports the profile's public key
 * as a key pair of its own, tagged with the marker tags and the build id, and deletes it when the
 * build ends; the builder still accepts the profile's private key.
 *
 * @param ec2For a new EC2 client for a region; the site closes it
 * @param infrastructureFor the infrastructure service over a region's VPC service, which sets up the packer network
 * @param profileRegion the region the profile's key pair [profileKeyName] lives in
 * @param profileKeyFile the profile's private key
 */
class AwsBuildSites(
    private val ec2For: (region: String) -> Ec2Client,
    private val infrastructureFor: (VpcService) -> AwsInfrastructureService,
    private val eventBus: EventBus,
    private val externalIpService: ExternalIpService,
    private val profileRegion: String,
    private val profileKeyName: String,
    private val profileKeyFile: File,
) : BuildSites {
    override fun open(
        region: String,
        buildId: String,
    ): BuildSite {
        val ec2 = ec2For(region)
        return runCleaningUpOnFailure(cleanup = { ec2.close() }) {
            infrastructureFor(EC2VpcService(ec2, eventBus))
                .ensurePackerInfrastructure(Constants.Network.SSH_PORT, externalIpService.getExternalCidr())
            val keyPair =
                when (region) {
                    profileRegion -> null
                    else -> importBuildKey(ec2, region, buildId)
                }
            Ec2BuildSite(region, keyPair ?: profileKeyName, keyPair, Ec2BuilderInstances(ec2, buildId), ec2)
        }
    }

    /** Imports the profile's public key into [region] under a name of this build's, and returns that name. */
    private fun importBuildKey(
        ec2: Ec2Client,
        region: String,
        buildId: String,
    ): String {
        val name = Constants.Packer.BUILD_KEY_PAIR_PREFIX + buildId
        val tags = (Constants.Vpc.MARKER_TAGS + (Constants.Packer.BUILD_ID_TAG_KEY to buildId)).map { (key, value) -> tag(key, value) }
        withAwsRetry("import-build-key-pair") {
            ec2.importKeyPair(
                ImportKeyPairRequest
                    .builder()
                    .keyName(name)
                    .publicKeyMaterial(SdkBytes.fromUtf8String(OpenSshPublicKey.of(profileKeyFile)))
                    .tagSpecifications(
                        TagSpecification
                            .builder()
                            .resourceType(ResourceType.KEY_PAIR)
                            .tags(tags)
                            .build(),
                    ).build(),
            )
        }
        println(
            "Build region $region is not the profile's ($profileRegion): imported the profile's key as key pair $name " +
                "for this build. SSH in with ${profileKeyFile.absolutePath}; the key pair is deleted when the build ends.",
        )
        return name
    }

    private fun tag(
        key: String,
        value: String,
    ): Tag =
        Tag
            .builder()
            .key(key)
            .value(value)
            .build()

    /** One build's site: deletes the key pair imported for it, if any, then closes the client. */
    private class Ec2BuildSite(
        override val region: String,
        override val keyPairName: String,
        private val importedKeyPair: String?,
        override val builders: BuilderInstances,
        private val ec2: Ec2Client,
    ) : BuildSite {
        override fun close() {
            try {
                importedKeyPair?.let { name ->
                    runCatchingException {
                        withAwsRetry("delete-build-key-pair") { ec2.deleteKeyPair(DeleteKeyPairRequest.builder().keyName(name).build()) }
                    }.onSuccess { println("Deleted the build's key pair $name in $region.") }
                        .onFailure {
                            println(
                                "Could not delete the build's key pair $name in $region (${it.messageOrString()}). " +
                                    "Delete it: aws ec2 delete-key-pair --region $region --key-name $name",
                            )
                        }
                }
            } finally {
                ec2.close()
            }
        }
    }
}
