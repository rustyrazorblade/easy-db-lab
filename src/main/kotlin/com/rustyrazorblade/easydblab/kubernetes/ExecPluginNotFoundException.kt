package com.rustyrazorblade.easydblab.kubernetes

/**
 * A kubeconfig user's exec auth plugin that cannot be found, so no request would carry a credential.
 *
 * [ExecPluginCheck] throws it from `KubeApi.loadConfig()`, before any client is built, and
 * [KubernetesErrorHandler] reports it as a `credentials` failure. It can never succeed on a retry:
 * the plugin stays missing until the user installs it.
 *
 * @property plugin the exec command as the kubeconfig names it
 * @property user the kubeconfig user that runs it
 * @property context the context being loaded
 * @property searched the `PATH` searched for a bare name, or the resolved file for a path
 * @property installHint the kubeconfig's `installHint`, when it has one
 */
class ExecPluginNotFoundException(
    val plugin: String,
    val user: String,
    val context: String,
    val searched: String,
    val installHint: String?,
) : IllegalStateException(
        "The exec auth plugin '$plugin' of kubeconfig user '$user' (context '$context') was not found " +
            (if (plugin.isPathStyle()) "at $searched" else "on PATH: $searched"),
    )
