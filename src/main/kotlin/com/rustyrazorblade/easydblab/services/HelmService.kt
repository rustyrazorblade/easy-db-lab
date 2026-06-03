package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import java.io.File
import java.util.UUID

interface HelmService {
    fun repoAdd(
        host: Host,
        name: String,
        url: String,
    )

    fun upgradeInstall(
        host: Host,
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
        host: Host,
        release: String,
        namespace: String,
    )

    fun releaseExists(
        host: Host,
        release: String,
        namespace: String,
    ): Boolean
}

class DefaultHelmService(
    private val remoteOps: RemoteOperationsService,
) : HelmService {
    override fun repoAdd(
        host: Host,
        name: String,
        url: String,
    ) {
        run(host, listOf("repo", "add", name, url, "--force-update"))
        run(host, listOf("repo", "update", name))
    }

    override fun upgradeInstall(
        host: Host,
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
        val remoteValuesPath =
            if (valuesFile != null) {
                val remotePath = "/tmp/helm-values-${UUID.randomUUID()}.yaml"
                remoteOps.upload(host, valuesFile.toPath(), remotePath)
                remotePath
            } else {
                null
            }

        try {
            val args =
                buildList {
                    addAll(listOf("upgrade", "--install", release, chart))
                    addAll(listOf("--namespace", namespace))
                    if (createNamespace) add("--create-namespace")
                    version?.let { addAll(listOf("--version", it)) }
                    remoteValuesPath?.let { addAll(listOf("--values", it)) }
                    values.forEach { (k, v) -> addAll(listOf("--set", "$k=$v")) }
                    if (wait) addAll(listOf("--wait", "--timeout", timeout))
                }
            run(host, args)
        } finally {
            if (remoteValuesPath != null) {
                remoteOps.executeRemotely(host, "rm -f $remoteValuesPath", output = false)
            }
        }
    }

    override fun uninstall(
        host: Host,
        release: String,
        namespace: String,
    ) {
        run(host, listOf("uninstall", release, "-n", namespace, "--ignore-not-found"))
    }

    override fun releaseExists(
        host: Host,
        release: String,
        namespace: String,
    ): Boolean {
        val response =
            remoteOps.executeRemotely(
                host,
                KubectlService.withKubeconfig("helm list -n $namespace -f '^$release\$' -q"),
                output = false,
            )
        return response.text.trim().isNotEmpty()
    }

    private fun run(
        host: Host,
        args: List<String>,
    ) {
        remoteOps.executeRemotely(host, KubectlService.withKubeconfig((listOf("helm") + args).joinToString(" ")))
    }
}
