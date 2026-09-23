package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
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
        val interpolate: Boolean = false,
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

    /**
     * Deletes Kubernetes objects in [namespace] (default `default`), in one of two forms.
     *
     * - By name: [kind] and [name] name one object. [ignoreNotFound] decides whether a missing
     *   object fails the step.
     * - By label: [selector] is a label selector and [kinds] the kinds it applies to. Every object
     *   of those kinds the selector matches is deleted; nothing matching is not an error and prints
     *   nothing, while a failed cluster query fails the step.
     *
     * A step that mixes the two forms, or has neither, is rejected when `kit.yaml` loads.
     */
    @Serializable
    @SerialName("delete")
    data class Delete(
        val kind: String = "",
        val name: String = "",
        val kinds: List<String> = emptyList(),
        val selector: String = "",
        val namespace: String? = null,
        @SerialName("ignore-not-found")
        val ignoreNotFound: Boolean = true,
    ) : InstallStep {
        /** True for the by-label form. */
        val bySelector: Boolean get() = selector.isNotBlank()

        init {
            val byName = kind.isNotBlank() && name.isNotBlank() && kinds.isEmpty() && selector.isBlank()
            val byLabel = bySelector && kinds.isNotEmpty() && kind.isBlank() && name.isBlank()
            require(byName || byLabel) {
                "delete step needs either kind and name, or selector and kinds (got kind='$kind', name='$name', " +
                    "kinds=$kinds, selector='$selector')"
            }
        }
    }

    /**
     * Creates the kit's local PersistentVolumes on the [nodeType] nodes' NVMe.
     *
     * [storageSize] is the PV capacity; blank means the `STORAGE_SIZE` kit variable. [ifSet]
     * names a kit variable: when it is non-blank and that variable is blank or absent, the step
     * does nothing, so a kit whose volume is optional (memcached extstore) creates no PV when the
     * feature is off.
     */
    @Serializable
    @SerialName("platform-pvs")
    data class PlatformPvs(
        val count: Int? = null,
        @SerialName("node-type")
        val nodeType: String = "db",
        @SerialName("volume-claim-template-name")
        val volumeClaimTemplateName: String = "data",
        @SerialName("storage-class")
        val storageClass: String = Constants.K8s.LOCAL_STORAGE_WFC_CLASS,
        @SerialName("storage-size")
        val storageSize: String = "",
        @SerialName("if-set")
        val ifSet: String = "",
    ) : InstallStep

    @Serializable
    @SerialName("platform-pvs-delete")
    data class PlatformPvsDelete(
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
