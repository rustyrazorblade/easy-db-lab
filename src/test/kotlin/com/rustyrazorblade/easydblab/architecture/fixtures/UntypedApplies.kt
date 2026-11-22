package com.rustyrazorblade.easydblab.architecture.fixtures

import com.rustyrazorblade.easydblab.services.K8sManifestOperations
import java.nio.file.Path

/**
 * Deliberate violations for [com.rustyrazorblade.easydblab.architecture.NoUntypedApplyTest]: each
 * call applies objects without the ownership labels, which the guard must report.
 */
object UntypedApplies {
    fun applyAll(manifests: K8sManifestOperations) {
        manifests.applyYaml("kind: ConfigMap")
        manifests.applyManifests(Path.of("manifests"))
        manifests.applyManifestFromResources("k8s/thing.yaml")
    }
}
