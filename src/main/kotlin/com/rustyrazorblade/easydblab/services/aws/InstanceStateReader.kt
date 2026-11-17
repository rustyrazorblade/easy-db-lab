package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.providers.aws.InstanceDetails
import com.rustyrazorblade.easydblab.providers.aws.InstanceId

/**
 * The two EC2 reads `up`'s shared instance watcher makes on every poll, one request each for at
 * most [batchSize] instances. It is a port so the watcher can be tested with a fake EC2 layer;
 * [EC2InstanceService] is the one adapter.
 */
interface InstanceStateReader {
    /** The most instance IDs one call of either read accepts. */
    val batchSize: Int

    /**
     * The state of each of [ids] EC2 knows. An instance EC2 does not know yet is left out, not an
     * error: a just-launched instance can take a few seconds to become visible.
     */
    fun describeStates(ids: List<InstanceId>): List<InstanceDetails>

    /** Which of [ids] have passed both their system and instance status checks. */
    fun passedStatusChecks(ids: List<InstanceId>): Set<InstanceId>
}
