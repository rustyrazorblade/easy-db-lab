package com.rustyrazorblade.easydblab.kubernetes

import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.internal.KubeConfigUtils
import java.nio.file.Path

/**
 * Where the Kubernetes API is: the kubeconfig file and the context pinned in it.
 *
 * This is the cluster contract's `api` field — the only thing a K8s client needs to reach the
 * cluster. It replaces the control-host coupling: the client connects to the API server named by
 * [context] in [kubeconfig], with no knowledge of the node easy-db-lab provisioned.
 */
data class KubeApi(
    val kubeconfig: Path,
    val context: String,
)

/**
 * The Fabric8 [Config] for this address: [KubeApi.kubeconfig] loaded by path with [KubeApi.context].
 *
 * Loading by path keeps the file location on the config, so relative certificate and exec-plugin
 * paths resolve against the kubeconfig's directory; the JVM-global kubeconfig property and the
 * `KUBECONFIG` env var are never read. A context the file does not hold fails fast naming the
 * context, the path and the contexts it does hold, rather than yielding a config with no master URL.
 */
fun KubeApi.loadConfig(): Config {
    val file = kubeconfig.toFile()
    val contexts = KubeConfigUtils.parseConfig(file).contexts.orEmpty()
    require(contexts.any { it.name == context }) {
        "Context '$context' not found in kubeconfig ${file.absolutePath}. " +
            "Available contexts: ${contexts.joinToString(", ") { it.name }}"
    }
    return Config.fromKubeconfig(context, file)
}
