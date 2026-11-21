package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import java.io.File

/** What the kit steps and commands ask of helm, wherever it runs; [DefaultHelmService] runs it through [ClusterTooling]. */
interface HelmService {
    fun repoAdd(
        name: String,
        url: String,
    )

    fun upgradeInstall(
        release: String,
        chart: String,
        namespace: String,
        version: String? = null,
        values: Map<String, String> = emptyMap(),
        valuesFile: File? = null,
        createNamespace: Boolean = false,
        wait: Boolean = false,
        timeout: String = "5m",
    )

    fun uninstall(
        release: String,
        namespace: String,
    )

    fun releaseExists(
        release: String,
        namespace: String,
    ): Boolean
}

/**
 * Runs helm through [ClusterTooling]. It builds argument lists of raw values and never quotes one
 * itself; [ClusterTooling] gets each value to helm unchanged.
 */
class DefaultHelmService(
    private val tooling: ClusterTooling,
) : HelmService {
    override fun repoAdd(
        name: String,
        url: String,
    ) {
        tooling.run(ClusterTool.HELM, listOf("repo", "add", name, url, "--force-update"))
        tooling.run(ClusterTool.HELM, listOf("repo", "update", name))
    }

    override fun upgradeInstall(
        release: String,
        chart: String,
        namespace: String,
        version: String?,
        values: Map<String, String>,
        valuesFile: File?,
        createNamespace: Boolean,
        wait: Boolean,
        timeout: String,
    ) {
        val args =
            buildList {
                addAll(listOf("upgrade", "--install", release, chart))
                addAll(listOf("--namespace", namespace))
                if (createNamespace) add("--create-namespace")
                version?.let { addAll(listOf("--version", it)) }
                if (valuesFile != null) addAll(listOf("--values", Constants.ClusterTooling.STDIN_ARGUMENT))
                values.forEach { (k, v) -> addAll(listOf("--set", "$k=$v")) }
                if (wait) addAll(listOf("--wait", "--timeout", timeout))
            }
        tooling.run(ClusterTool.HELM, args, stdin = valuesFile?.readText())
    }

    override fun uninstall(
        release: String,
        namespace: String,
    ) {
        tooling.run(ClusterTool.HELM, listOf("uninstall", release, "-n", namespace, "--ignore-not-found"))
    }

    override fun releaseExists(
        release: String,
        namespace: String,
    ): Boolean {
        val output = tooling.run(ClusterTool.HELM, listOf("list", "-n", namespace, "-f", "^$release\$", "-q"), echo = false)
        return output.trim().isNotEmpty()
    }
}
