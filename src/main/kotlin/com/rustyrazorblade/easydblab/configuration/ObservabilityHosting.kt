package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EnvFromSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Where an observability workload keeps its local state: the TSDB head and WAL, pre-flush chunks and
 * blocks, Pyroscope's v2 metastore, Grafana's database. Never `emptyDir`: Pyroscope's metastore is the
 * only record of its blocks, so losing it on a reschedule would lose data.
 */
sealed interface BackendStorage {
    /** A directory on the node the workload is placed on, as on the nodes of an AWS cluster. */
    data object HostPath : BackendStorage

    /**
     * One claim per workload, on a StorageClass that reclaims with `Retain`, for an environment
     * whose nodes are not easy-db-lab's to write to. The StorageClass comes from #1035.
     *
     * @property storageClass the StorageClass the claim is provisioned from; it reclaims with `Retain`.
     * @property size the capacity each claim requests.
     */
    data class RetainedClaim(
        val storageClass: String,
        val size: Quantity,
    ) : BackendStorage
}

/** How a backend authenticates to the object store. */
sealed interface StoreCredentials {
    /** The node's instance role, through the default credential chain, as on AWS. Nothing is mounted. */
    data object InstanceRole : StoreCredentials

    /**
     * Nothing provides store credentials: an environment with no instance role (K3s in Docker, a
     * brought cluster) until #1061 provisions a Secret for it. Nothing is mounted, as for [InstanceRole],
     * but no reader can mistake it for a role the node has.
     */
    data object NotProvided : StoreCredentials

    /**
     * Credentials from a Secret, mounted into the backend container's environment with `envFrom`.
     * #1061 populates it.
     *
     * @property secretName the Secret holding the store credentials.
     */
    data class FromSecret(
        val secretName: String,
    ) : StoreCredentials
}

/**
 * One workload's state volume, as the builder declares it: the volume's name in the pod and the host
 * directory it lives in on a node easy-db-lab owns.
 *
 * @property workload the workload's name; a claim for it is named `<workload>-data`.
 * @property volumeName the pod volume that holds the state.
 * @property hostPath the directory on the node when the environment provides host paths.
 * @property owner the uid the workload's process runs as and must write the state as, or null for
 *   one that runs as root. On a host path `up` chowns the directory to it; on a claim it is the
 *   pod's `fsGroup`, since a provisioned volume's root directory belongs to root.
 */
data class StateVolume(
    val workload: String,
    val volumeName: String,
    val hostPath: String,
    val owner: Long? = null,
)

/**
 * How the observability backends and Grafana are hosted on the cluster's environment: where they are
 * placed, where their state lives, and how the backends reach the object store. Resolved once from the
 * cluster state; the builders apply it and never ask which environment they run on.
 *
 * @property placement the node selector, tolerations and network.
 * @property storage where local state lives.
 * @property credentials how the backends authenticate to the store.
 * @property publishing how the backends' and Grafana's Services reach callers outside the pod network.
 */
data class ObservabilityHosting(
    val placement: Placement,
    val storage: BackendStorage,
    val credentials: StoreCredentials,
    val publishing: ServicePublishing = ServicePublishing.ClusterIp,
) {
    /**
     * [deployment] hosted here: placed by [placement], its [state] volume on [storage], preceded by
     * the claim that volume needs, if any. The backends ([isBackend]) also run as the namespace's
     * default service account and get the store-credentials Secret in the container named for
     * [StateVolume.workload].
     *
     * @throws IllegalArgumentException when the pod declares no volume named [StateVolume.volumeName],
     *   which would otherwise leave the state on whatever the pod declared.
     */
    fun host(
        deployment: Deployment,
        state: StateVolume,
        isBackend: Boolean,
    ): List<HasMetadata> {
        val declared =
            deployment.spec.template.spec.volumes
                .orEmpty()
                .map { it.name }
        require(state.volumeName in declared) {
            "${state.workload} declares no volume named ${state.volumeName} for its state; it declares $declared."
        }
        val claim = claimFor(state, deployment.metadata.namespace)
        val placed = placement.applyTo(deployment.spec.template.spec)
        val pod = if (claim != null) ownedBy(placed, state.owner) else placed
        val hosted =
            DeploymentBuilder(deployment)
                .editSpec()
                .editTemplate()
                .withSpec(pod)
                .editSpec()
                .withVolumes(pod.volumes.map { if (it.name == state.volumeName) stateVolume(state, claim) else it })
                .endSpec()
                .endTemplate()
                .endSpec()
                .build()
        return listOfNotNull(claim, if (isBackend) withBackendIdentity(hosted, state.workload) else hosted)
    }

    /** [pod] with [owner] as its `fsGroup`, keeping the rest of its security context. */
    private fun ownedBy(
        pod: PodSpec,
        owner: Long?,
    ): PodSpec =
        when (owner) {
            null -> pod
            else -> {
                val context = pod.securityContext?.let { PodSecurityContextBuilder(it) } ?: PodSecurityContextBuilder()
                PodSpecBuilder(pod).withSecurityContext(context.withFsGroup(owner).build()).build()
            }
        }

    private fun claimFor(
        state: StateVolume,
        namespace: String,
    ): PersistentVolumeClaim? =
        when (storage) {
            BackendStorage.HostPath -> null
            is BackendStorage.RetainedClaim ->
                PersistentVolumeClaimBuilder()
                    .withNewMetadata()
                    .withName("${state.workload}-data")
                    .withNamespace(namespace)
                    .addToLabels("app.kubernetes.io/name", state.workload)
                    .endMetadata()
                    .withNewSpec()
                    .withAccessModes(Constants.K8s.READ_WRITE_ONCE)
                    .withStorageClassName(storage.storageClass)
                    .withNewResources()
                    .addToRequests("storage", storage.size)
                    .endResources()
                    .endSpec()
                    .build()
        }

    private fun stateVolume(
        state: StateVolume,
        claim: PersistentVolumeClaim?,
    ): Volume =
        when (claim) {
            null ->
                VolumeBuilder()
                    .withName(state.volumeName)
                    .withHostPath(HostPathVolumeSourceBuilder().withPath(state.hostPath).withType("DirectoryOrCreate").build())
                    .build()
            else ->
                VolumeBuilder()
                    .withName(state.volumeName)
                    .withNewPersistentVolumeClaim()
                    .withClaimName(claim.metadata.name)
                    .endPersistentVolumeClaim()
                    .build()
        }

    /** [deployment] as the default service account, the store Secret in the container named [workload]. */
    private fun withBackendIdentity(
        deployment: Deployment,
        workload: String,
    ): Deployment {
        val pod = deployment.spec.template.spec
        val containers =
            when (credentials) {
                StoreCredentials.InstanceRole, StoreCredentials.NotProvided -> pod.containers
                is StoreCredentials.FromSecret -> {
                    require(pod.containers.any { it.name == workload }) {
                        "$workload has no container named $workload to give the store credentials to."
                    }
                    pod.containers.map { container ->
                        when (container.name) {
                            workload ->
                                ContainerBuilder(container)
                                    .addToEnvFrom(
                                        EnvFromSourceBuilder()
                                            .withNewSecretRef()
                                            .withName(credentials.secretName)
                                            .endSecretRef()
                                            .build(),
                                    ).build()
                            else -> container
                        }
                    }
                }
            }
        return DeploymentBuilder(deployment)
            .editSpec()
            .editTemplate()
            .editSpec()
            .withServiceAccountName(DEFAULT_SERVICE_ACCOUNT)
            .withContainers(containers)
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
    }

    companion object {
        private const val DEFAULT_SERVICE_ACCOUNT = "default"

        /**
         * A cluster easy-db-lab built on AWS: the control node, its disks, its instance role, and its
         * client ports on fixed NodePorts.
         */
        val AWS =
            ObservabilityHosting(
                Placement.CONTROL_NODE,
                BackendStorage.HostPath,
                StoreCredentials.InstanceRole,
                ServicePublishing.NodePorts,
            )

        /**
         * The hosting the cluster contract resolves for [state]'s recorded environment. The placement
         * follows the environment; storage stays [AWS]'s until the off-AWS store and its StorageClass
         * exist (#1035), and off EC2 the credentials are [StoreCredentials.NotProvided] until #1061, so the
         * retained-claim and Secret branches are rendered only by tests.
         */
        fun from(state: ClusterState): ObservabilityHosting = ClusterContract.of(state).hosting
    }
}
