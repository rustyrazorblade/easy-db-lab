package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.runCatchingException
import io.fabric8.kubernetes.api.model.Config
import io.fabric8.kubernetes.client.internal.KubeConfigUtils
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Path

/**
 * Parses the kubeconfig at [path] into Fabric8's typed kubeconfig model.
 *
 * The parser lives in Fabric8's `internal` package, which carries no compatibility promise. Every
 * reader of a kubeconfig goes through this one function, so a Fabric8 upgrade that moves or changes
 * it is fixed in one place.
 *
 * Every K8s call reads this file, so a failure names the path:
 * - a file that cannot be read fails with an [IOException] naming the path, with the cause kept, so
 *   a missing file is still recognised as a cluster that was never reached
 *   ([com.rustyrazorblade.easydblab.services.ClusterReach]);
 * - a file that cannot be parsed (a truncated or hand-mangled kubeconfig) fails with an
 *   [IllegalStateException] naming the path and the kind of failure only. The parser's message and
 *   cause are dropped, because they quote the start of the file, and a kubeconfig holds client keys
 *   and tokens.
 */
fun parseKubeconfig(path: Path): Config {
    val file = path.toFile()
    if (!file.isFile) {
        throw IOException("Cannot read the kubeconfig at ${file.absolutePath}", FileNotFoundException(file.absolutePath))
    }
    return runCatchingException { KubeConfigUtils.parseConfig(file) }
        .getOrElse { throw unparseable(file.absolutePath, it) }
}

private fun unparseable(
    path: String,
    cause: Throwable,
): IllegalStateException = IllegalStateException("The kubeconfig at $path could not be parsed (${cause.javaClass.simpleName})")
