package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import software.amazon.awssdk.awscore.exception.AwsServiceException

/**
 * A RunInstances call that AWS refused with `InsufficientInstanceCapacity`: it has no capacity for
 * [instanceType] in [availabilityZone] right now.
 *
 * Capacity cannot be checked ahead of a launch, so the launch path raises this to tell a capacity
 * shortage apart from any other provisioning failure. The operator's fix is a different zone or
 * instance type, not a bug report.
 *
 * @property serverType the node role being launched (`db`, `app` or `control`)
 * @property instanceType the EC2 instance type AWS had no capacity for
 * @property availabilityZone the zone of the subnet the launch targeted, or null when it could not be resolved
 * @property subnetId the subnet the launch targeted
 */
class InsufficientInstanceCapacityException(
    val serverType: String,
    val instanceType: String,
    val availabilityZone: String?,
    val subnetId: String,
    cause: Throwable,
) : RuntimeException(
        "AWS has no capacity for $instanceType ($serverType nodes) in ${availabilityZone ?: "the zone of $subnetId"}",
        cause,
    ) {
    companion object {
        /** True when [e] is AWS's capacity refusal. */
        fun isCapacityRefusal(e: AwsServiceException): Boolean = e.awsErrorDetails()?.errorCode() == Constants.AWS.EC2_INSUFFICIENT_CAPACITY
    }
}
