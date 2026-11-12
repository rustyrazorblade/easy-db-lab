package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorAgentInstaller
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorAgentJarFile
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorManifestBuilder
import io.fabric8.kubernetes.api.model.HasMetadata

/**
 * The account compactor's part of the observability deploy, on every cluster, telemetry redirect
 * included: it prepares the control node (scratch directories, the agent jar at its SHA-256 path)
 * and builds the `account-compactor` resources for the jar it put there and the bucket's region.
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
     * @param bucket the account bucket the compactors work on.
     * @throws Exception when the jar is missing from the install, or a remote command or the
     *   region lookup fails.
     */
    fun prepare(
        controlNode: ClusterHost,
        bucket: String,
    ): List<HasMetadata> {
        val remoteJar = installer.install(controlNode.toHost(), agentJar())
        return builder.buildAllResources(remoteJar, bucketRegion(bucket))
    }
}
