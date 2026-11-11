package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.providers.aws.EBSConfig
import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig
import com.rustyrazorblade.easydblab.providers.aws.SubnetId
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice
import software.amazon.awssdk.services.ec2.model.HttpTokensState
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification
import software.amazon.awssdk.services.ec2.model.InstanceMetadataOptionsRequest
import software.amazon.awssdk.services.ec2.model.ResourceType
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.ec2.model.TagSpecification
import software.amazon.awssdk.services.ec2.model.VolumeType

/**
 * The ordinals one RunInstances call launches: the instance with launch index `k` takes ordinal
 * `first + k * stride`. The call tags its instances with [first] and [stride], so an instance's
 * alias is known from its launch tags alone, even when its own `Name` tag was never set.
 */
data class OrdinalRun(
    val first: Int,
    val stride: Int,
    val count: Int,
) {
    /** The ordinals, in launch-index order. */
    val ordinals: List<Int> get() = (0 until count).map { first + it * stride }

    companion object {
        /** The ordinal of launch index [launchIndex], from a launch's [tags]; null if it has none. */
        fun ordinalOf(
            tags: Map<String, String>,
            launchIndex: Int?,
        ): Int? {
            val first = tags[Constants.Scale.FIRST_ORDINAL_TAG]?.toIntOrNull()
            val stride = tags[Constants.Scale.ORDINAL_STRIDE_TAG]?.toIntOrNull()
            return if (first == null || stride == null || launchIndex == null) null else first + launchIndex * stride
        }
    }
}

/**
 * Builds the RunInstances request for a launch. The real launch ([EC2InstanceService]) and its
 * pre-launch dry run ([Ec2AccountCapacity]) both use it, so the dry run asks AWS about exactly the
 * request the launch will make: the same AMI, type, subnet, security group, instance profile, key,
 * tags, volumes and metadata options.
 */
object RunInstancesRequests {
    private const val IMDS_HOP_LIMIT = 2
    private val IOPS_VOLUME_TYPES = setOf("gp3", "io1", "io2")

    /**
     * The request that launches the instances of [run] for [config] in [subnetId]. [clientToken]
     * makes a retried call idempotent.
     */
    fun build(
        config: InstanceCreationConfig,
        subnetId: SubnetId,
        run: OrdinalRun,
        clientToken: String,
        dryRun: Boolean = false,
    ): RunInstancesRequest {
        val builder =
            RunInstancesRequest
                .builder()
                .imageId(config.amiId)
                .instanceType(config.instanceType)
                .minCount(run.count)
                .maxCount(run.count)
                .clientToken(clientToken)
                .keyName(config.keyName)
                .securityGroupIds(config.securityGroupId)
                .subnetId(subnetId)
                .iamInstanceProfile(
                    IamInstanceProfileSpecification
                        .builder()
                        .name(config.iamInstanceProfile)
                        .build(),
                ).tagSpecifications(tagSpecifications(config, run))
                // All node types need hop limit 2 so containers (K3s pods) can reach IMDS. In
                // particular the non-hostNetwork cilium-operator pod may schedule on a db/app node
                // and must reach IMDS to obtain credentials for ENI allocation.
                .metadataOptions(
                    InstanceMetadataOptionsRequest
                        .builder()
                        .httpTokens(HttpTokensState.REQUIRED)
                        .httpPutResponseHopLimit(IMDS_HOP_LIMIT)
                        .httpEndpoint("enabled")
                        .build(),
                )
        if (dryRun) builder.dryRun(true)

        val blockDeviceMappings = blockDeviceMappings(config.ebsConfig)
        if (blockDeviceMappings.isNotEmpty()) {
            builder.blockDeviceMappings(blockDeviceMappings)
        }
        return builder.build()
    }

    /** One EC2 tag. */
    fun tag(
        key: String,
        value: String,
    ): Tag =
        Tag
            .builder()
            .key(key)
            .value(value)
            .build()

    /**
     * The tag specifications for the instances and the volumes launched with them: every tag
     * except `Name`, which is per instance and set after launch. The volumes carry the instances'
     * tags, so YACE finds a cluster's EBS volumes by `ClusterId` as it finds its instances, and
     * discovery finds an instance by `ClusterId` and `ServerType` from launch on, and its alias
     * from [run]'s ordinal tags and its launch index.
     */
    private fun tagSpecifications(
        config: InstanceCreationConfig,
        run: OrdinalRun,
    ): List<TagSpecification> {
        val tags =
            (
                config.tags +
                    mapOf(
                        "Cluster" to config.clusterName,
                        "ServerType" to config.serverType.serverType,
                        Constants.Scale.FIRST_ORDINAL_TAG to run.first.toString(),
                        Constants.Scale.ORDINAL_STRIDE_TAG to run.stride.toString(),
                    )
            ).map { (key, value) -> tag(key, value) }

        return listOf(ResourceType.INSTANCE, ResourceType.VOLUME).map { type ->
            TagSpecification
                .builder()
                .resourceType(type)
                .tags(tags)
                .build()
        }
    }

    private fun blockDeviceMappings(ebsConfig: EBSConfig?): List<BlockDeviceMapping> {
        if (ebsConfig == null) {
            return emptyList()
        }

        val ebsBuilder =
            EbsBlockDevice
                .builder()
                .volumeSize(ebsConfig.volumeSize)
                .volumeType(VolumeType.fromValue(ebsConfig.volumeType))
                .deleteOnTermination(true)

        if (ebsConfig.iops != null && ebsConfig.volumeType in IOPS_VOLUME_TYPES) {
            ebsBuilder.iops(ebsConfig.iops)
        }

        if (ebsConfig.throughput != null && ebsConfig.volumeType == "gp3") {
            ebsBuilder.throughput(ebsConfig.throughput)
        }

        return listOf(
            BlockDeviceMapping
                .builder()
                .deviceName(ebsConfig.deviceName)
                .ebs(ebsBuilder.build())
                .build(),
        )
    }
}
