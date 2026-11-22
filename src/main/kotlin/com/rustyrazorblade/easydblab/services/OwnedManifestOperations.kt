package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScopeSource
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.utils.KubernetesSerialization

/**
 * Marks every object the CLI applies, all through the typed [applyResource], as easy-db-lab's and as
 * the current cluster's, so a later sweep finds exactly what one cluster owns (design D6). The two
 * ownership labels go on the object's top-level `metadata.labels` only: a selector is immutable, so a
 * label there would force a delete and recreate, and a label in a pod template would roll every
 * workload. The scope is read on each apply, so a REPL or server picks up a changed workspace. The
 * caller's object is copied, never changed.
 *
 * Only [applyResource] is labelled. The untyped paths (`applyYaml`, `applyManifests`,
 * `applyManifestFromResources`) go to the delegate as they are, so they must not be used for
 * anything a cluster owns: no main source calls them today, and `architecture/NoUntypedApplyTest`
 * fails if one starts to.
 */
class OwnedManifestOperations(
    private val delegate: K8sManifestOperations,
    private val scopeSource: InstallScopeSource,
) : K8sManifestOperations by delegate {
    override fun applyResource(resource: HasMetadata): Result<Unit> = delegate.applyResource(owned(resource))

    private fun owned(resource: HasMetadata): HasMetadata =
        serialization.clone(resource).apply {
            metadata.labels = metadata.labels.orEmpty() + scopeSource.current().labels
        }

    private companion object {
        val serialization = KubernetesSerialization()
    }
}
