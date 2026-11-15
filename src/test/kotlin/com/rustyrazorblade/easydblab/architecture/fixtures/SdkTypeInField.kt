package com.rustyrazorblade.easydblab.architecture.fixtures

import software.amazon.awssdk.services.s3.S3Client

/**
 * A deliberate violation for [com.rustyrazorblade.easydblab.architecture.PackageBoundaryTest]: a class outside the
 * AWS adapter packages that holds an SDK type. The test imports it alone to prove the rule fails on it.
 */
@Suppress("unused")
class SdkTypeInField(
    val client: S3Client,
)
