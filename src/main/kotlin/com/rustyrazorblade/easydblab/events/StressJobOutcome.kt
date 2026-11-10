package com.rustyrazorblade.easydblab.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a stress job finished, as `Stress.JobCompleted` carries it.  On the wire it is the lowercase
 * string `succeeded` or `failed`, which consumers of the Redis channel match on.
 */
@Serializable
enum class StressJobOutcome(
    val wireName: String,
) {
    @SerialName("succeeded")
    SUCCEEDED("succeeded"),

    @SerialName("failed")
    FAILED("failed"),
}
