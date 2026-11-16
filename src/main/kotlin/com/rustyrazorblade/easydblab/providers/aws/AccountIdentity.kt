package com.rustyrazorblade.easydblab.providers.aws

import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest
import software.amazon.awssdk.services.sts.model.StsException

/**
 * Who the current AWS credentials belong to. Callers that only need the account ID (AMI lookup,
 * OpenSearch policies, cluster provisioning) or a credential check depend on this, not on a class
 * that also holds IAM and S3 clients.
 */
interface AccountIdentity {
    /**
     * The AWS account ID for the authenticated credentials, cached after the first lookup.
     *
     * @throws StsException if unable to retrieve account information
     */
    fun getAccountId(): String

    /**
     * Validates the credentials with STS GetCallerIdentity and caches the account ID.
     *
     * @throws StsException if credentials are invalid or lack permissions
     * @throws SdkServiceException if there's a service error
     */
    fun checkPermissions()
}

/**
 * [AccountIdentity] backed by STS. It is the only class that holds the [StsClient].
 */
class StsAccountIdentity(
    private val stsClient: StsClient,
) : AccountIdentity {
    private var cachedAccountId: String? = null

    override fun getAccountId(): String =
        cachedAccountId
            ?: stsClient
                .getCallerIdentity(GetCallerIdentityRequest.builder().build())
                .account()
                .also {
                    cachedAccountId = it
                    log.debug { "Retrieved and cached account ID: $it" }
                }

    override fun checkPermissions() {
        try {
            val request = GetCallerIdentityRequest.builder().build()
            val response = stsClient.getCallerIdentity(request)

            // Cache the account ID for later use
            cachedAccountId = response.account()

            log.info { "AWS credentials validated successfully" }
            log.info { "Account: ${response.account()}" }
            log.info { "User ARN: ${response.arn()}" }
            log.info { "User ID: ${response.userId()}" }
        } catch (e: StsException) {
            log.error(e) { "AWS credential validation failed: ${e.message}" }
            throw e
        } catch (e: SdkServiceException) {
            log.error(e) { "AWS service error during credential validation: ${e.message}" }
            throw e
        }
    }

    private companion object {
        private val log = KotlinLogging.logger {}
    }
}
