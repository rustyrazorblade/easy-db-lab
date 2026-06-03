package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService

interface KubectlService {
    fun createNamespace(
        host: Host,
        name: String,
    )

    fun applyUrl(
        host: Host,
        url: String,
    )

    fun applyKustomize(
        host: Host,
        url: String,
    )

    fun wait(
        host: Host,
        kind: String,
        name: String,
        condition: String,
        namespace: String,
        timeout: String,
    )

    fun delete(
        host: Host,
        kind: String,
        name: String,
        namespace: String,
        ignoreNotFound: Boolean,
    )

    companion object {
        /** Prepend KUBECONFIG env var so remote CLI tools (helm, kubectl) find the cluster. */
        fun withKubeconfig(command: String) = "KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG} $command"
    }
}

class DefaultKubectlService(
    private val remoteOps: RemoteOperationsService,
) : KubectlService {
    override fun createNamespace(
        host: Host,
        name: String,
    ) {
        remoteOps.executeRemotely(
            host,
            KubectlService.withKubeconfig("kubectl create namespace $name --dry-run=client -o yaml | kubectl apply -f -"),
        )
    }

    override fun applyUrl(
        host: Host,
        url: String,
    ) {
        run(host, listOf("apply", "-f", url))
    }

    override fun applyKustomize(
        host: Host,
        url: String,
    ) {
        run(host, listOf("apply", "-k", url))
    }

    override fun wait(
        host: Host,
        kind: String,
        name: String,
        condition: String,
        namespace: String,
        timeout: String,
    ) {
        val args =
            buildList {
                addAll(listOf("wait", "--for=condition=$condition", "$kind/$name"))
                addAll(listOf("-n", namespace))
                addAll(listOf("--timeout", timeout))
            }
        run(host, args)
    }

    override fun delete(
        host: Host,
        kind: String,
        name: String,
        namespace: String,
        ignoreNotFound: Boolean,
    ) {
        val args =
            buildList {
                addAll(listOf("delete", "$kind/$name", "-n", namespace))
                if (ignoreNotFound) add("--ignore-not-found")
            }
        run(host, args)
    }

    private fun run(
        host: Host,
        args: List<String>,
    ) {
        remoteOps.executeRemotely(host, KubectlService.withKubeconfig((listOf("kubectl") + args).joinToString(" ")))
    }
}
