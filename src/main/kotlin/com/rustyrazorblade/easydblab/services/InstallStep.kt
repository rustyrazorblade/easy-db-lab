package com.rustyrazorblade.easydblab.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface InstallStep {
    @Serializable
    @SerialName("helm-repo")
    data class HelmRepo(
        val name: String,
        val url: String,
    ) : InstallStep

    @Serializable
    @SerialName("helm")
    data class Helm(
        val chart: String,
        val release: String,
        val namespace: String = "default",
        val version: String? = null,
        val values: Map<String, String> = emptyMap(),
        @SerialName("values-file")
        val valuesFile: String = "",
    ) : InstallStep

    @Serializable
    @SerialName("helm-uninstall")
    data class HelmUninstall(
        val release: String,
        val namespace: String = "default",
    ) : InstallStep

    @Serializable
    @SerialName("namespace")
    data class Namespace(
        val name: String,
    ) : InstallStep

    @Serializable
    @SerialName("manifest")
    data class Manifest(
        val template: String,
    ) : InstallStep

    @Serializable
    @SerialName("manifest-url")
    data class ManifestUrl(
        val url: String,
    ) : InstallStep

    @Serializable
    @SerialName("kustomize")
    data class Kustomize(
        val url: String,
    ) : InstallStep

    @Serializable
    @SerialName("wait")
    data class Wait(
        val kind: String,
        val name: String,
        val namespace: String? = null,
        val condition: String = "Available",
        val timeout: String = "300s",
    ) : InstallStep

    @Serializable
    @SerialName("delete")
    data class Delete(
        val kind: String,
        val name: String,
        val namespace: String? = null,
        @SerialName("ignore-not-found")
        val ignoreNotFound: Boolean = true,
    ) : InstallStep

    @Serializable
    @SerialName("platform-pvs")
    data class PlatformPvs(
        val count: Int? = null,
        @SerialName("node-type")
        val nodeType: String = "db",
    ) : InstallStep

    @Serializable
    @SerialName("configmap")
    data class ConfigMap(
        val name: String,
        val namespace: String = "default",
        val data: Map<String, String> = emptyMap(),
    ) : InstallStep

    @Serializable
    @SerialName("label")
    data class Label(
        val labels: Map<String, String>,
        @SerialName("node-type")
        val nodeType: String = "db",
    ) : InstallStep

    @Serializable
    @SerialName("exec")
    data class Exec(
        val pod: String,
        val namespace: String = "default",
        val command: List<String>,
    ) : InstallStep

    @Serializable
    @SerialName("shell")
    data class Shell(
        val script: String,
    ) : InstallStep
}
