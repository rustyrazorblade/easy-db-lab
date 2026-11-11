package com.rustyrazorblade.easydblab.services.aws

import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.ec2.model.TagSpecification

/**
 * Builds the tag specification that an EC2 create call applies to the resource it creates.
 *
 * Shared by [EC2VpcService] and [EC2SecurityGroupService], so every resource they create is
 * tagged the same way.
 */
internal fun buildTagSpecification(
    resourceType: String,
    tags: Map<String, String>,
): TagSpecification {
    val awsTags =
        tags.map { (key, value) ->
            Tag
                .builder()
                .key(key)
                .value(value)
                .build()
        }

    return TagSpecification
        .builder()
        .resourceType(resourceType)
        .tags(awsTags)
        .build()
}
