package com.rustyrazorblade.easydblab.kubernetes

import io.fabric8.kubernetes.api.model.Config
import io.fabric8.kubernetes.client.internal.KubeConfigUtils
import java.io.IOException
import java.nio.file.Path

/**
 * Parses the kubeconfig at [path] into Fabric8's typed kubeconfig model.
 *
 * The parser lives in Fabric8's `internal` package, which carries no compatibility promise. Every
 * reader of a kubeconfig goes through this one function, so a Fabric8 upgrade that moves or changes
 * it is fixed in one place.
 *
 * A file that cannot be read or parsed (a truncated or hand-mangled kubeconfig) fails with an
 * [IllegalStateException] naming the path and the parse problem, with the parser's exception as the
 * cause, instead of the YAML scanner's bare message: every K8s call reads this file, so the error has
 * to say which file is broken.
 */
fun parseKubeconfig(path: Path): Config {
    val file = path.toFile()
    return try {
        KubeConfigUtils.parseConfig(file)
    } catch (e: IOException) {
        throw unparseable(file.absolutePath, e)
    } catch (e: RuntimeException) {
        throw unparseable(file.absolutePath, e)
    }
}

private fun unparseable(
    path: String,
    cause: Exception,
): IllegalStateException =
    IllegalStateException(
        "Cannot parse kubeconfig $path: ${cause.message.orEmpty().lines().first()}",
        cause,
    )
