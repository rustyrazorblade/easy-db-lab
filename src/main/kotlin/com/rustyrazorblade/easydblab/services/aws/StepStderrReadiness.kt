package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.services.ObjectStore
import java.time.Instant

/**
 * Decides whether the `stderr.gz` EMR has uploaded for a failed step is its final copy.
 *
 * EMR uploads a running step's logs every few minutes and once more after the step ends, so a
 * copy uploaded before the end can stop short of the exception that failed the step. Only a copy
 * uploaded after the step's end is final. Without an end time there is nothing to compare, so any
 * uploaded copy is taken.
 */
object StepStderrReadiness {
    /** True when [stderr] exists and was uploaded after [stepEnd]. */
    fun isFinal(
        stderr: ObjectStore.FileInfo?,
        stepEnd: Instant?,
    ): Boolean =
        when {
            stderr == null -> false
            stepEnd == null -> true
            else -> Instant.parse(stderr.lastModified).isAfter(stepEnd)
        }
}
