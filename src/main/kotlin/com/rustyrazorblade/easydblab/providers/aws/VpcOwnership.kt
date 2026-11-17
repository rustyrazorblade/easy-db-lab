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
    /** The tag `up` puts on a VPC it creates, and on the resources of that cluster, naming the cluster. */
    const val CLUSTER_ID_TAG = "ClusterId"

    /**
     * True when [vpcTags] carry the easy-db-lab marker tag and a non-blank ClusterId, which `up`
     * sets together on every VPC it creates.
     */
    fun createdByEasyDbLab(vpcTags: Map<String, String>): Boolean =
        vpcTags[Constants.Vpc.TAG_KEY] == Constants.Vpc.TAG_VALUE &&
            !vpcTags[CLUSTER_ID_TAG].isNullOrBlank()
}
