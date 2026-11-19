package com.rustyrazorblade.easydblab.kernel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Describes a failure a cloud provider's adapter recognises, so the command layer can show a
 * typed message with a remedy without importing the provider's SDK.
 *
 * Each provider (AWS today, GCP and Azure later) registers one handler. A handler claims an
 * exception by its type alone, never by the active target, so it classifies before any cluster
 * state or profile exists. It must take no dependencies that need a profile.
 *
 * Lives in `kernel/` because commands, services and providers all share it and `kernel/` depends
 * on nothing else in the project, so no package cycle is possible.
 */
fun interface ProviderErrorHandler {
    /** Returns the description when this provider owns [error], or null when it does not. */
    fun describe(error: Throwable): ErrorDescription?
}

/**
 * The kind of provider failure a user can act on. Only the kinds that have a remedy are defined.
 */
@Serializable
enum class ProviderErrorKind {
    /** The provider rejected the identity: expired, missing or invalid credentials. */
    @SerialName("credentials")
    CREDENTIALS,

    /** The identity is valid but lacks permission for the operation. */
    @SerialName("permission-denied")
    PERMISSION_DENIED,

    /** The provider or its local daemon could not be reached or is not running. */
    @SerialName("unavailable")
    UNAVAILABLE,
}

/**
 * What a [ProviderErrorHandler] says about a failure.
 *
 * @property provider display name of the provider, for example "AWS"
 * @property kind what class of failure it is
 * @property detail the provider's own message
 * @property remedy what the user should do next
 * @property response what the provider's service answered, when the failure is a service answer
 */
data class ErrorDescription(
    val provider: String,
    val kind: ProviderErrorKind,
    val detail: String,
    val remedy: String,
    val response: ProviderResponse? = null,
)

/**
 * The identifying facts of a provider service's error answer. The detail message alone loses them, and
 * they are what tells two failures apart and what the provider's support asks for.
 *
 * @property service the service that answered, for example "Sts"
 * @property errorCode the provider's error code, for example "ExpiredToken"
 * @property statusCode the HTTP status of the answer
 * @property requestId the provider's id for the failed request
 */
@Serializable
data class ProviderResponse(
    val service: String? = null,
    val errorCode: String? = null,
    val statusCode: Int? = null,
    val requestId: String? = null,
) {
    /** One line naming each fact that is known, for example "service Sts, code ExpiredToken, HTTP 403". */
    fun summary(): String =
        listOfNotNull(
            service?.let { "service $it" },
            errorCode?.let { "code $it" },
            statusCode?.let { "HTTP $it" },
            requestId?.let { "request id $it" },
        ).joinToString(", ")
}
