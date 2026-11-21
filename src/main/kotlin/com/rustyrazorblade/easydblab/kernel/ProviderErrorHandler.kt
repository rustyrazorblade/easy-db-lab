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

    /**
     * The identity was allowed to make the request, but a policy refused its content: a Kubernetes
     * Pod Security level, an admission webhook or a ValidatingAdmissionPolicy. Granting permissions
     * does not fix it; changing the object or the policy does.
     */
    @SerialName("rejected")
    REJECTED,
}

/**
 * What a [ProviderErrorHandler] says about a failure.
 *
 * @property provider display name of the provider, for example "AWS"
 * @property kind what class of failure it is
 * @property detail the provider's own message
 * @property remedy what the user should do next
 * @property response what the provider's service answered, when the failure is a service answer
 * @property request the request that failed, when the handler knows it
 * @property policy the policy that refused the request, for a [ProviderErrorKind.REJECTED] failure
 */
data class ErrorDescription(
    val provider: String,
    val kind: ProviderErrorKind,
    val detail: String,
    val remedy: String,
    val response: ProviderResponse? = null,
    val request: ProviderRequest? = null,
    val policy: ProviderPolicy? = null,
)

/**
 * The request a provider refused, in the provider's own terms, so a user can grant exactly what
 * is missing. For Kubernetes the verb is the RBAC verb (`list`, `watch`), not the HTTP method,
 * when the server named one.
 *
 * @property verb what the request tried to do, for example "list" or "create"
 * @property resource the resource, with its subresource when there is one, for example "pods/exec"
 * @property namespace the namespace, or null at the cluster scope
 * @property name the object's name, when the request named one
 * @property principal the identity the provider evaluated, when it named one
 */
@Serializable
data class ProviderRequest(
    val verb: String,
    val resource: String,
    val namespace: String? = null,
    val name: String? = null,
    val principal: String? = null,
) {
    /** The resource and, when known, its object name, for example "pods/x". */
    fun target(): String = name?.let { "$resource/$it" } ?: resource
}

/**
 * The policy that refused a request, and why.
 *
 * @property name the policy, for example "PodSecurity" or a webhook's name
 * @property level the level the policy enforced, for example "restricted:latest"
 * @property violations one entry per reason the policy gave
 */
@Serializable
data class ProviderPolicy(
    val name: String,
    val level: String? = null,
    val violations: List<String> = emptyList(),
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
