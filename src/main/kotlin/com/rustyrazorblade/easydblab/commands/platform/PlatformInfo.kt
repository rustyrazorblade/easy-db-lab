package com.rustyrazorblade.easydblab.commands.platform

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.KitStorage
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.KitStorageSource
import io.fabric8.kubernetes.api.model.PersistentVolume
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * The kit local PVs `platform info` found free, by node pool, or why they could not be read.
 *
 * Only meaningful on [KitStorage.LocalVolumes]; a provisioned class has no PVs to count.
 */
sealed interface PvAvailability {
    /** The `Available` PVs of each pool (`db`, `app`); a pool with none is absent. */
    data class Counted(
        val byPool: Map<String, Int>,
    ) : PvAvailability

    /** The PVs could not be listed; [reason] says why. */
    data class Unreadable(
        val reason: String,
    ) : PvAvailability
}

/**
 * Shows K8s platform substrate details: the kit storage, node pools, selectors, the available
 * kit PVs of each pool on local volumes, and an example Helm values snippet.
 *
 * Uses println() directly — this is a read-only display command. No state changes;
 * nothing an external system needs to be aware of.
 */
@RequireProfileSetup
@RequiresProxy
@Command(
    name = "info",
    description = ["Show K8s platform substrate details: kit storage, node pools, selectors"],
)
class PlatformInfo : PicoBaseCommand() {
    private val k8sService: K8sService by inject()
    private val kitStorageSource: KitStorageSource by inject()

    override fun execute() {
        val dbNodeCount = clusterState.hosts[ServerType.Cassandra]?.size ?: 0
        val appNodeCount = clusterState.hosts[ServerType.Stress]?.size ?: 0
        val kitStorage = KitStorage.from(clusterState) { kitStorageSource.defaultClass() }
        val availablePvs =
            when (kitStorage) {
                is KitStorage.LocalVolumes ->
                    k8sService.listKitLocalPersistentVolumes().fold(
                        onSuccess = { PvAvailability.Counted(availablePvsByPool(it)) },
                        onFailure = { PvAvailability.Unreadable(it.message ?: it.javaClass.simpleName) },
                    )
                is KitStorage.Provisioned, is KitStorage.NoStorageClass -> null
            }

        println(buildInfoText(dbNodeCount, appNodeCount, kitStorage, availablePvs))

        val exampleHelmValues =
            PlatformInfo::class.java
                .getResourceAsStream("example-helm-values.yaml")
                ?.bufferedReader()
                ?.readText()
                ?: error("Resource not found: example-helm-values.yaml")

        println("\nExample helm values:\n$exampleHelmValues")
    }

    companion object {
        private const val AVAILABLE = "Available"

        /** The `Available` PVs among [pvs], counted by the node pool their affinity's `type` names. */
        fun availablePvsByPool(pvs: List<PersistentVolume>): Map<String, Int> =
            pvs
                .filter { it.status?.phase == AVAILABLE }
                .mapNotNull(::poolOf)
                .groupingBy { it }
                .eachCount()

        private fun poolOf(pv: PersistentVolume): String? =
            pv.spec
                ?.nodeAffinity
                ?.required
                ?.nodeSelectorTerms
                .orEmpty()
                .flatMap { it.matchExpressions.orEmpty() }
                .firstOrNull { it.key == Constants.NODE_TYPE_LABEL }
                ?.values
                ?.firstOrNull()

        fun buildInfoText(
            dbNodeCount: Int,
            appNodeCount: Int,
            kitStorage: KitStorage,
            availablePvs: PvAvailability? = null,
        ): String {
            val dbPool = ServerType.Cassandra.serverType
            val appPool = ServerType.Stress.serverType
            return listOfNotNull(
                "Platform substrate info:",
                "  Kit storage: ${describe(kitStorage)}",
                "  DB nodes: $dbNodeCount  (nodeSelector: type=$dbPool${pvCount(availablePvs, dbPool)})",
                "  App nodes: $appNodeCount  (nodeSelector: type=$appPool${pvCount(availablePvs, appPool)})",
                (availablePvs as? PvAvailability.Unreadable)?.let { "  Available PVs: could not be read (${it.reason})" },
                "  Ordinal label: ${Constants.NODE_ORDINAL_LABEL}",
            ).joinToString("\n")
        }

        private fun pvCount(
            availablePvs: PvAvailability?,
            pool: String,
        ): String =
            when (availablePvs) {
                is PvAvailability.Counted -> ", available PVs: ${availablePvs.byPool[pool] ?: 0}"
                is PvAvailability.Unreadable, null -> ""
            }

        private fun describe(kitStorage: KitStorage): String =
            when (kitStorage) {
                is KitStorage.LocalVolumes ->
                    "local volumes, StorageClass ${Constants.K8s.KIT_LOCAL_CLASS_PREFIX}<kit>, " +
                        "default size ${kitStorage.defaultSize}"
                is KitStorage.Provisioned ->
                    "StorageClass ${kitStorage.storageClass}, default size ${kitStorage.defaultSize}"
                is KitStorage.NoStorageClass ->
                    "no StorageClass (none recorded and the cluster has no default), default size ${kitStorage.defaultSize}"
            }
    }
}
