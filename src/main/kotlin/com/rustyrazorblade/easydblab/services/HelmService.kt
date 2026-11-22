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
            }
        val trailing =
            buildList {
                values.forEach { (k, v) -> addAll(listOf("--set", "$k=$v")) }
                if (wait) addAll(listOf("--wait", "--timeout", timeout))
            }
        if (valuesFile == null) {
            tooling.run(ClusterTool.HELM, args + trailing)
        } else {
            val withValues = args.map(ToolArg::Value) + ToolArg.Value("--values") + ToolArg.Input + trailing.map(ToolArg::Value)
            tooling.runWithInput(ClusterTool.HELM, withValues, input = valuesFile.readText())
        }
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
        // `-f` is a regular expression; the name is matched literally and whole, so `my.app` does
        // not match `myxapp` and `app` does not match `myapp`. `--all` lists every state: a release
        // an interrupted install left pending still holds its name.
        val filter = "^${quoteMeta(release)}\$"
        val output = tooling.run(ClusterTool.HELM, listOf("list", "-n", namespace, "-f", filter, "--all", "-q"), echo = false)
        return output.trim().isNotEmpty()
    }

    private companion object {
        /** [text] as a Go regular expression that matches exactly [text], escaped as Go's `regexp.QuoteMeta` does. */
        fun quoteMeta(text: String): String =
            buildString {
                text.forEach { c -> if (c in Constants.ClusterTooling.HELM_REGEX_METACHARACTERS) append('\\').append(c) else append(c) }
            }
    }
}
