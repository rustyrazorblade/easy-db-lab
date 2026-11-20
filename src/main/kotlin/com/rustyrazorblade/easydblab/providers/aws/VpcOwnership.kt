package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants

/**
 * Decides whether a VPC is easy-db-lab's to delete, from its tags alone.
 *
 * `down <vpc-id>` with no cluster state cannot know whether the VPC came from `init --vpc` (the
 * user's, shared with their own resources) or was created by `up`. The tags `up` puts on every VPC
 * it creates are the only evidence left, so a VPC without them is kept.
 */
object VpcOwnership {
    /**
     * True when [vpcTags] carry the easy-db-lab marker tag and either a non-blank ClusterId, which
     * `up` sets with it on every cluster VPC, or the packer VPC's name, which is how the packer
     * VPC (it has no cluster) is told apart.
     */
    fun createdByEasyDbLab(vpcTags: Map<String, String>): Boolean =
        vpcTags[Constants.Vpc.LEGACY_TAG_KEY] == Constants.Vpc.TAG_VALUE &&
            (
                !vpcTags[Constants.Vpc.CLUSTER_ID_TAG_KEY].isNullOrBlank() ||
                    vpcTags[Constants.Vpc.NAME_TAG_KEY] == Constants.Vpc.PACKER_VPC_NAME
            )
}
