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
}

/**
 * What a [ProviderErrorHandler] says about a failure.
 *
 * @property provider display name of the provider, for example "AWS"
 * @property kind what class of failure it is
 * @property detail the provider's own message
 * @property remedy what the user should do next
 */
data class ErrorDescription(
    val provider: String,
    val kind: ProviderErrorKind,
    val detail: String,
    val remedy: String,
)
