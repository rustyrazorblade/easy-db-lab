package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * The condition a conditional PUT carries, which decides what an error from S3 means.
 */
enum class WriteCondition {
    /** `If-None-Match: *`: write only where no object is. */
    IF_NONE_MATCH,

    /** `If-Match: <etag>`: replace only the version that was read. */
    IF_MATCH,
}

/**
 * Tells a conditional PUT that S3 refused because its condition does not hold from one that
 * failed for any other reason.
 *
 * A refused condition is a result the caller decides on; every other failure is an error and must
 * not be mistaken for one, or a missing bucket or an aborted operation would look like an object
 * that is already there. S3 refuses with 412, with 409 `ConditionalRequestConflict` when a
 * concurrent conditional write to the key won, and, for `If-Match` only, with 404 `NoSuchKey` when
 * the object it named was removed. Every other 404 (a missing bucket, an unknown code, no code at
 * all) is an error.
 *
 * A refusal does not say why: the key may hold another writer's object, the writer's own (an
 * earlier attempt committed and lost its response), or nothing yet (a write is in flight there).
 * A caller that must tell these apart reads the key back.
 */
object ConditionalWriteRefusal {
    /** Whether [error], from a PUT carrying [condition], is S3 refusing that condition. */
    fun isRefused(
        condition: WriteCondition,
        error: S3Exception,
    ): Boolean {
        val code = error.awsErrorDetails()?.errorCode()
        return when (error.statusCode()) {
            Constants.HttpStatus.PRECONDITION_FAILED -> true
            Constants.HttpStatus.CONFLICT -> code == Constants.S3.CONDITIONAL_REQUEST_CONFLICT
            Constants.HttpStatus.NOT_FOUND -> condition == WriteCondition.IF_MATCH && code == Constants.S3.NO_SUCH_KEY
            else -> false
        }
    }
}
