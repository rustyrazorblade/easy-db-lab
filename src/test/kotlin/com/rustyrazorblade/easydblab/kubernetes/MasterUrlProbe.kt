package com.rustyrazorblade.easydblab.kubernetes

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import java.nio.file.Path

/**
 * Prints the master URL [ProxiedKubernetesClientFactory] builds for a kubeconfig and context.
 *
 * [ProxiedKubernetesClientFactoryTest] runs it in a child JVM whose environment carries a
 * `KUBECONFIG` naming another file. That checks that the env var is ignored without changing this
 * JVM's environment, which every other test shares.
 */
object MasterUrlProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        // As Main does: the factory logs, and the banner would precede the URL on stdout.
        KotlinLoggingConfiguration.logStartupMessage = false
        val (kubeconfig, context) = args
        ProxiedKubernetesClientFactory().createClient(KubeApi(Path.of(kubeconfig), context)).use { client ->
            print(client.configuration.masterUrl)
        }
    }
}
