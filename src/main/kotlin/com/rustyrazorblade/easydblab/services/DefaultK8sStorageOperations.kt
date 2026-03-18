package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.PersistentVolume
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Implementation of storage-related K8s operations: ConfigMaps, persistent volumes,
 * StorageClasses, and StatefulSet scaling.
 */
class DefaultK8sStorageOperations(
    private val clientProvider: K8sClientProvider,
    private val eventBus: EventBus,
) : K8sStorageOperations {
    override fun createClickHouseS3ConfigMap(
        controlHost: ClusterHost,
        namespace: String,
        s3EndpointUrl: String,
    ): Result<Unit> =
        runCatching {
            log.info { "Creating ClickHouse S3 ConfigMap in namespace $namespace" }
            log.info { "S3 endpoint: $s3EndpointUrl" }

            clientProvider.createClient(controlHost).use { client ->
                val existing =
                    client
                        .configMaps()
                        .inNamespace(namespace)
                        .withName(Constants.ClickHouse.S3_CONFIG_NAME)
                        .get()

                if (existing != null) {
                    log.info { "Deleting existing ConfigMap ${Constants.ClickHouse.S3_CONFIG_NAME}" }
                    client
                        .configMaps()
                        .inNamespace(namespace)
                        .withName(Constants.ClickHouse.S3_CONFIG_NAME)
                        .delete()
                }

                val configMap =
                    ConfigMapBuilder()
                        .withNewMetadata()
                        .withName(Constants.ClickHouse.S3_CONFIG_NAME)
                        .withNamespace(namespace)
                        .addToLabels("app.kubernetes.io/name", "clickhouse-server")
                        .endMetadata()
                        .addToData("CLICKHOUSE_S3_ENDPOINT", s3EndpointUrl)
                        .build()

                client
                    .configMaps()
                    .inNamespace(namespace)
                    .resource(configMap)
                    .create()
                log.info { "Created ConfigMap ${Constants.ClickHouse.S3_CONFIG_NAME}" }
            }

            eventBus.emit(Event.K8s.ClickHouseS3ConfigMapCreated)
        }

    override fun scaleStatefulSet(
        controlHost: ClusterHost,
        namespace: String,
        statefulSetName: String,
        replicas: Int,
    ): Result<Unit> =
        runCatching {
            log.info { "Scaling StatefulSet $statefulSetName in namespace $namespace to $replicas replicas" }

            clientProvider.createClient(controlHost).use { client ->
                client
                    .apps()
                    .statefulSets()
                    .inNamespace(namespace)
                    .withName(statefulSetName)
                    .scale(replicas)

                log.info { "StatefulSet $statefulSetName scaled to $replicas replicas" }
            }

            eventBus.emit(Event.K8s.StatefulSetScaled(statefulSetName, replicas))
        }

    override fun createConfigMap(
        controlHost: ClusterHost,
        namespace: String,
        name: String,
        data: Map<String, String>,
        labels: Map<String, String>,
    ): Result<Unit> =
        runCatching {
            log.info { "Creating ConfigMap $name in namespace $namespace" }

            clientProvider.createClient(controlHost).use { client ->
                val existing =
                    client
                        .configMaps()
                        .inNamespace(namespace)
                        .withName(name)
                        .get()

                if (existing != null) {
                    log.info { "Deleting existing ConfigMap $name" }
                    client
                        .configMaps()
                        .inNamespace(namespace)
                        .withName(name)
                        .delete()
                }

                val configMapBuilder =
                    ConfigMapBuilder()
                        .withNewMetadata()
                        .withName(name)
                        .withNamespace(namespace)

                labels.forEach { (key, value) ->
                    configMapBuilder.addToLabels(key, value)
                }

                val metadataFinished = configMapBuilder.endMetadata()

                data.forEach { (key, value) ->
                    metadataFinished.addToData(key, value)
                }

                val configMap = metadataFinished.build()

                client
                    .configMaps()
                    .inNamespace(namespace)
                    .resource(configMap)
                    .create()

                log.info { "Created ConfigMap: $name" }
            }

            eventBus.emit(Event.K8s.ConfigMapCreated(name))
        }

    override fun deleteConfigMap(
        controlHost: ClusterHost,
        namespace: String,
        name: String,
    ): Result<Unit> =
        runCatching {
            log.info { "Deleting ConfigMap $name from namespace $namespace" }

            clientProvider.createClient(controlHost).use { client ->
                client
                    .configMaps()
                    .inNamespace(namespace)
                    .withName(name)
                    .delete()

                log.info { "Deleted ConfigMap: $name" }
            }

            eventBus.emit(Event.K8s.ConfigMapDeleted(name))
        }

    override fun createLocalPersistentVolumes(
        controlHost: ClusterHost,
        config: PersistentVolumeConfig,
    ): Result<Unit> =
        runCatching {
            log.info { "Creating ${config.count} Local PVs for ${config.dbName}" }

            clientProvider.createClient(controlHost).use { client ->
                for (ordinal in 0 until config.count) {
                    val pvcName = "${config.volumeClaimTemplateName}-${config.dbName}-$ordinal"
                    val pvName = pvcName

                    val existing = client.persistentVolumes().withName(pvName).get()
                    if (existing != null) {
                        clearStaleClaimRefUid(client, existing, pvName)
                        continue
                    }

                    val pv =
                        PersistentVolumeBuilder()
                            .withNewMetadata()
                            .withName(pvName)
                            .addToLabels("app.kubernetes.io/name", config.dbName)
                            .addToLabels("app.kubernetes.io/component", "data")
                            .endMetadata()
                            .withNewSpec()
                            .addToCapacity("storage", Quantity(config.storageSize))
                            .withAccessModes("ReadWriteOnce")
                            .withPersistentVolumeReclaimPolicy("Retain")
                            .withStorageClassName(config.storageClass)
                            .withNewLocal()
                            .withPath(config.localPath)
                            .endLocal()
                            .withNewClaimRef()
                            .withName(pvcName)
                            .withNamespace(config.namespace)
                            .endClaimRef()
                            .withNewNodeAffinity()
                            .withNewRequired()
                            .addNewNodeSelectorTerm()
                            .addNewMatchExpression()
                            .withKey(Constants.NODE_ORDINAL_LABEL)
                            .withOperator("In")
                            .withValues(ordinal.toString())
                            .endMatchExpression()
                            .endNodeSelectorTerm()
                            .endRequired()
                            .endNodeAffinity()
                            .endSpec()
                            .build()

                    client.persistentVolumes().resource(pv).create()
                    log.info { "Created PV $pvName pre-bound to PVC $pvcName on node ordinal $ordinal" }
                }

                eventBus.emit(Event.K8s.LocalPvsCreated(config.count, config.dbName))
            }
        }

    /**
     * If a PV's claimRef has a UID pointing to a deleted PVC, clear just the UID.
     * The claimRef name/namespace remain correct (matching the PVC the StatefulSet will create),
     * so clearing the UID restores the PV to a pre-bound state that accepts new PVCs.
     */
    private fun clearStaleClaimRefUid(
        client: KubernetesClient,
        existing: PersistentVolume,
        pvName: String,
    ) {
        val phase = existing.status?.phase ?: "Unknown"
        val claimRef = existing.spec?.claimRef
        if (claimRef?.uid == null) {
            log.debug { "PV $pvName already exists (phase=$phase, no claimRef UID), skipping" }
            return
        }

        log.debug {
            "PV $pvName: phase=$phase claimRef=${claimRef.namespace}/${claimRef.name} uid=${claimRef.uid}"
        }

        val boundPvc =
            client
                .persistentVolumeClaims()
                .inNamespace(claimRef.namespace)
                .withName(claimRef.name)
                .get()

        if (boundPvc != null && boundPvc.status?.phase == "Bound") {
            log.debug { "PV $pvName bound to active PVC ${claimRef.name}, skipping" }
            return
        }

        log.info { "PV $pvName has stale claimRef UID (PVC ${claimRef.name} no longer exists), clearing to allow rebinding" }
        client.persistentVolumes().withName(pvName).edit { pv ->
            pv.spec.claimRef.uid = null
            pv.spec.claimRef.resourceVersion = null
            pv
        }
        log.info { "PV $pvName claimRef UID cleared, PV should transition to Available for rebinding" }
    }

    override fun ensureLocalStorageClass(controlHost: ClusterHost): Result<Unit> =
        runCatching {
            log.info { "Ensuring local-storage StorageClass exists" }

            clientProvider.createClient(controlHost).use { client ->
                val existing =
                    client
                        .storage()
                        .v1()
                        .storageClasses()
                        .withName(Constants.K8s.LOCAL_STORAGE_CLASS)
                        .get()

                if (existing != null) {
                    if (existing.volumeBindingMode != "Immediate") {
                        log.info {
                            "StorageClass ${Constants.K8s.LOCAL_STORAGE_CLASS} has binding mode " +
                                "${existing.volumeBindingMode}, recreating with Immediate"
                        }
                        client
                            .storage()
                            .v1()
                            .storageClasses()
                            .withName(Constants.K8s.LOCAL_STORAGE_CLASS)
                            .delete()
                    } else {
                        log.info { "StorageClass ${Constants.K8s.LOCAL_STORAGE_CLASS} already exists" }
                        return@runCatching
                    }
                }

                val storageClass =
                    StorageClassBuilder()
                        .withNewMetadata()
                        .withName(Constants.K8s.LOCAL_STORAGE_CLASS)
                        .endMetadata()
                        .withProvisioner("kubernetes.io/no-provisioner")
                        .withVolumeBindingMode("Immediate")
                        .withReclaimPolicy("Retain")
                        .build()

                client
                    .storage()
                    .v1()
                    .storageClasses()
                    .resource(storageClass)
                    .create()

                log.info { "Created StorageClass ${Constants.K8s.LOCAL_STORAGE_CLASS}" }
                eventBus.emit(Event.K8s.StorageClassCreated)
            }
        }
}
