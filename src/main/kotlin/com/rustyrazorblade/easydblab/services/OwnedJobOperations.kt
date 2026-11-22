package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScopeSource
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.utils.KubernetesSerialization

/**
 * Marks every typed Job the CLI creates (stress and stress-command Jobs) as easy-db-lab's and as the
 * current cluster's, the same way [OwnedManifestOperations] marks applied objects, so a sweep by the
 * cluster label finds them (design D6). The labels go on the Job's top-level metadata only, never on
 * its pod template. The caller's Job is copied, never changed.
 */
class OwnedJobOperations(
    private val delegate: K8sJobOperations,
    private val scopeSource: InstallScopeSource,
) : K8sJobOperations by delegate {
    override fun createJob(
        namespace: String,
        job: Job,
    ): Result<String> = delegate.createJob(namespace, owned(job))

    private fun owned(job: Job): Job =
        serialization.clone(job).apply {
            metadata.labels = metadata.labels.orEmpty() + scopeSource.current().labels
        }

    private companion object {
        val serialization = KubernetesSerialization()
    }
}
