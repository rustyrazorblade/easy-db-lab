package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.KitStorage
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.shellQuote
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * The namespace the kits create their claims in. It is the owned default, not the cluster namespace
 * `init --namespace` records: the kits move to the cluster namespace in issue 1094 PR 2, and until
 * then a reopen filtered on the cluster namespace would find none of their Released volumes.
 */
private const val KIT_NAMESPACE = Constants.ContractDefaults.OWNED_NAMESPACE

/**
 * The volumes one `kit-storage` step reserves for a kit.
 *
 * @property kit the kit instance; its PVs are labelled with it and live under `/mnt/db1/<kit>`.
 * @property nodeType the pool (`db`, `app`) the PVs are on.
 * @property volumeClaimTemplateName the claim template the PVs serve; PVs are named
 *   `<template>-<kit>-<ordinal>` and live at `/mnt/db1/<kit>/<template>`, so a kit with two
 *   templates on one node keeps them apart.
 * @property size the capacity of each PV.
 * @property count how many PVs, from ordinal 0; null is one per node of the pool.
 * @property rebindReleased on a provisioned class, reopen the class's Released volumes for claims of
 *   the same name: for a kit whose operator deletes its claims on `stop` (CNPG, Altinity).
 */
data class KitVolumes(
    val kit: String,
    val nodeType: String,
    val volumeClaimTemplateName: String,
    val size: String,
    val count: Int? = null,
    val rebindReleased: Boolean = false,
)

/**
 * Prepares and releases a kit's storage as the environment's [KitStorage] says.
 *
 * On [KitStorage.LocalVolumes] it creates the kit's own no-provisioner class `edl-local-<kit>`, the
 * node directories and the local PVs of that class, so the kit's claims can bind only its PVs. On
 * [KitStorage.Provisioned] the cluster provisions the volumes: it checks that the class still
 * retains, before any claim of the kit exists, reporting a refusal as an event; for a kit that asks
 * it reopens the class's Released volumes; and it creates and deletes nothing.
 */
class KitStorageProvisioner(
    private val k8sService: K8sService,
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
) {
    private val retainCheck = StorageClassRetainCheck(k8sService)

    /** Readies [volumes] on [storage], in the cluster [state] describes. */
    fun provision(
        volumes: KitVolumes,
        storage: KitStorage,
        state: ClusterState,
    ) {
        when (storage) {
            is KitStorage.Provisioned -> {
                retainCheck.requireKitClass(storage.storageClass, eventBus)
                if (volumes.rebindReleased) reopenReleased(volumes.kit, storage.storageClass, state)
            }
            is KitStorage.LocalVolumes -> createLocalVolumes(volumes, storage.storageClassFor(volumes.kit), state)
        }
    }

    /**
     * Removes [kit]'s local volumes on [nodeType] as part of an explicit uninstall: its PVs, its
     * class and its directory on each node. On a provisioned class it does nothing; a claim or a
     * volume there is never deleted by easy-db-lab.
     */
    fun release(
        kit: String,
        nodeType: String,
        storage: KitStorage,
        state: ClusterState,
    ) {
        when (storage) {
            is KitStorage.Provisioned -> log.info { "Kit storage of $kit is on ${storage.storageClass}; nothing to release" }
            is KitStorage.LocalVolumes -> {
                // Checked before anything is deleted: the path goes to `sudo rm -rf` on each node.
                val kitPath = kitPath(kit)
                // Each delete must succeed before the next: a PV still bound, or a failed call,
                // stops the release before any data directory is removed.
                k8sService.deleteLocalPersistentVolumes(dbName = kit).getOrThrow()
                k8sService.deleteKitLocalStorageClass(storage.storageClassFor(kit)).getOrThrow()
                for (host in state.hosts[ServerType.from(nodeType)].orEmpty()) {
                    remoteOps.executeRemotely(host = host.toHost(), command = "sudo rm -rf ${kitPath.shellQuote()}")
                    log.info { "Deleted data directory $kitPath on ${host.alias}" }
                }
            }
        }
    }

    private fun createLocalVolumes(
        volumes: KitVolumes,
        storageClass: String,
        state: ClusterState,
    ) {
        val hosts = state.hosts[ServerType.from(volumes.nodeType)].orEmpty()
        check(hosts.isNotEmpty()) { "No ${volumes.nodeType} nodes found in cluster state for kit-storage step" }
        val count = volumes.count ?: hosts.size
        val path = "${kitPath(volumes.kit)}/${pathSegment(volumes.volumeClaimTemplateName, "claim template name")}"

        k8sService.ensureKitLocalStorageClass(storageClass, state.clusterId).getOrThrow()
        // kit-storage-delete removes the directory on uninstall; without this mkdir, a start after
        // an uninstall fails with "path does not exist" when the pod mounts the local PV.
        for (host in hosts.take(count)) {
            remoteOps.executeRemotely(host = host.toHost(), command = "sudo mkdir -p ${path.shellQuote()}")
            log.debug { "Ensured data directory $path exists on ${host.alias}" }
        }
        k8sService
            .createLocalPersistentVolumes(
                PersistentVolumeConfig(
                    dbName = volumes.kit,
                    localPath = path,
                    count = count,
                    storageSize = volumes.size,
                    storageClass = storageClass,
                    namespace = KIT_NAMESPACE,
                    clusterId = state.clusterId,
                    volumeClaimTemplateName = volumes.volumeClaimTemplateName,
                    nodeType = volumes.nodeType,
                ),
            ).getOrThrow()
    }

    /**
     * The operator deleted the kit's claims on `stop`, so their Retain volumes are Released with
     * the data. Reopened, each is taken again by the claim of the same name the next apply makes;
     * otherwise the class would provision an empty volume and strand the data.
     */
    private fun reopenReleased(
        kit: String,
        storageClass: String,
        state: ClusterState,
    ) {
        val reopened =
            k8sService
                .reopenReleasedVolumes(
                    storageClass,
                    KIT_NAMESPACE,
                ).getOrThrow()
        if (reopened.isNotEmpty()) log.info { "Reopened Released volumes $reopened of $storageClass for $kit" }
    }

    /** `/mnt/db1/<kit>`, for a [kit] name that stays one directory below it. */
    private fun kitPath(kit: String) = "${Constants.K8s.DB_MOUNT_PATH}/${pathSegment(kit, "kit name")}"

    /**
     * [value] as one directory name: these paths go to `sudo mkdir` and `sudo rm -rf` on every
     * node, so a blank name, a `/` or a `..` (which would widen the path) is refused.
     */
    private fun pathSegment(
        value: String,
        what: String,
    ): String {
        require(value.isNotBlank() && '/' !in value && value != "." && ".." !in value) {
            "Invalid $what '$value' for kit storage: it must be one directory name, not blank, with no '/' or '..'"
        }
        return value
    }
}
