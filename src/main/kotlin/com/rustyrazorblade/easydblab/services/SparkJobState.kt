package com.rustyrazorblade.easydblab.services

/**
 * The state of a Spark job, as the project reports it, independent of any cloud's step states.
 *
 * `EMRSparkService` maps EMR's step states onto it, so commands and the [SparkService] contract
 * name no SDK type. [UNKNOWN] is a state the provider reported that this version does not know,
 * or no state at all.
 */
enum class SparkJobState {
    PENDING,
    CANCEL_PENDING,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED,
    INTERRUPTED,
    UNKNOWN,
}
