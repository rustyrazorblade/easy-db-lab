package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.KitStorage
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

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
 */
data class KitVolumes(
    val kit: String,
    val nodeType: String,
    val volumeClaimTemplateName: String,
    val size: String,
    val count: Int? = null,
)

/**
 * Prepares and releases a kit's storage as the environment's [KitStorage] says.
 *
 * On [KitStorage.LocalVolumes] it creates the kit's own no-provisioner class `edl-local-<kit>`, the
 * node directories and the local PVs of that class, so the kit's claims can bind only its PVs. On
 * [KitStorage.Provisioned] the cluster provisions the volumes: it only checks that the class still
 * retains, before any claim of the kit exists, and creates and deletes nothing.
 */
class KitStorageProvisioner(
    private val k8sService: K8sService,
    private val remoteOps: RemoteOperationsService,
) {
    private val retainCheck = StorageClassRetainCheck(k8sService)

    /** Readies [volumes] on [storage], in the cluster [state] describes. */
    fun provision(
        volumes: KitVolumes,
        storage: KitStorage,
        state: ClusterState,
    ) {
        when (storage) {
            is KitStorage.Provisioned -> retainCheck.require(storage.storageClass, RetainedData.KitData)
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
                k8sService.deleteLocalPersistentVolumes(dbName = kit).getOrThrow()
                k8sService.deleteKitLocalStorageClass(storage.storageClassFor(kit)).getOrThrow()
                val kitPath = kitPath(kit)
                for (host in state.hosts[ServerType.from(nodeType)].orEmpty()) {
                    remoteOps.executeRemotely(host = host.toHost(), command = "sudo rm -rf $kitPath")
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
        val path = "${kitPath(volumes.kit)}/${volumes.volumeClaimTemplateName}"

        k8sService.ensureKitLocalStorageClass(storageClass, state.clusterId).getOrThrow()
        // kit-storage-delete removes the directory on uninstall; without this mkdir, a start after
        // an uninstall fails with "path does not exist" when the pod mounts the local PV.
        for (host in hosts.take(count)) {
            remoteOps.executeRemotely(host = host.toHost(), command = "sudo mkdir -p $path")
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
                    namespace = Constants.K8s.NAMESPACE,
                    volumeClaimTemplateName = volumes.volumeClaimTemplateName,
                    nodeType = volumes.nodeType,
                ),
            ).getOrThrow()
    }

    private fun kitPath(kit: String) = "${Constants.K8s.DB_MOUNT_PATH}/$kit"
}
