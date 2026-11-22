package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorAgentJarFile
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorManifestBuilder
import com.rustyrazorblade.easydblab.configuration.store.S3Backend
import com.rustyrazorblade.easydblab.configuration.store.StoreBackend
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.services.CompactorAgentInstaller
import io.fabric8.kubernetes.api.model.HasMetadata

/**
 * The account compactor's part of the observability deploy, on every cluster that stores on an
 * object store, telemetry redirect included: it prepares the control node (scratch directories,
 * the agent jar at its SHA-256 path) and builds the `account-compactor` resources for the jar it
 * put there and the account bucket's store.
 *
 * It exists so [DefaultObservabilityStackService] takes one collaborator for the compactor instead
 * of four.
 *
 * @property builder builds the Deployment and its ConfigMap.
 * @property installer prepares the control node.
 * @property bucketRegion finds a bucket's region; the compactors sign for it.
 * @property agentJar the lease agent jar this CLI was installed with.
 */
class CompactorStage(
    private val builder: CompactorManifestBuilder,
    private val installer: CompactorAgentInstaller,
    private val bucketRegion: (String) -> String,
    private val agentJar: () -> CompactorAgentJarFile,
) {
    /**
     * Prepares [controlNode] for the compactor and returns its resources, in apply order.
     *
     * @param clusterState the cluster whose account bucket and store prefix the compactors work on.
     * @throws Exception when the cluster has no account bucket, the jar is missing from the
     *   install, or a remote command or the region lookup fails.
     */
    fun prepare(
        controlNode: ClusterHost,
        clusterState: ClusterState,
        scope: InstallScope,
    ): List<HasMetadata> {
        val remoteJar = installer.install(controlNode.toHost(), agentJar())
        return builder.buildAllResources(scope, remoteJar, store(clusterState))
    }

    /**
     * The account bucket under the cluster's store prefix, in the bucket's own region: the bucket
     * is shared by every cluster in the account, so its region can differ from this cluster's.
     */
    private fun store(clusterState: ClusterState): StoreBackend {
        val bucket = ObservabilityStore.from(clusterState).bucket
        return S3Backend(bucket = bucket, prefix = StoreBackend.prefixOf(clusterState), region = bucketRegion(bucket))
    }
}
