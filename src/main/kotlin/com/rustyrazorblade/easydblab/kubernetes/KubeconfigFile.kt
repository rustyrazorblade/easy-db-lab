package com.rustyrazorblade.easydblab.kubernetes

import io.fabric8.kubernetes.api.model.Config
import io.fabric8.kubernetes.client.internal.KubeConfigUtils
import java.nio.file.Path

/**
 * Parses the kubeconfig at [path] into Fabric8's typed kubeconfig model.
 *
 * The parser lives in Fabric8's `internal` package, which carries no compatibility promise. Every
 * reader of a kubeconfig goes through this one function, so a Fabric8 upgrade that moves or changes
 * it is fixed in one place.
 */
fun parseKubeconfig(path: Path): Config = KubeConfigUtils.parseConfig(path.toFile())
