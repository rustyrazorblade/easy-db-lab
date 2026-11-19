package com.rustyrazorblade.easydblab.providers.aws

import software.amazon.awssdk.core.exception.SdkException

/**
 * Whether this failure came from the AWS SDK: a service error or a client-side failure of a call.
 *
 * Code outside the AWS adapter packages must not name an SDK type, not even in a `catch`
 * (`PackageBoundaryTest`). A caller that handles only AWS failures, and lets any other failure
 * propagate, captures with `runCatchingException` and rethrows when this is false.
 */
fun Throwable.isAwsFailure(): Boolean = this is SdkException

/** Rethrows this failure unless [isAwsFailure]; returns it otherwise, for the caller to handle. */
fun Throwable.requireAwsFailure(): Throwable = if (isAwsFailure()) this else throw this
