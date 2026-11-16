package com.rustyrazorblade.easydblab.commands.platform

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.KitStorage
import com.rustyrazorblade.easydblab.configuration.ServerType
import picocli.CommandLine.Command

/**
 * Shows K8s platform substrate details: the kit storage, node pools, selectors, and
 * an example Helm values snippet.
 *
 * Uses println() directly — this is a read-only display command. No state changes;
 * nothing an external system needs to be aware of.
 */
@RequireProfileSetup
@Command(
    name = "info",
    description = ["Show K8s platform substrate details: kit storage, node pools, selectors"],
)
class PlatformInfo : PicoBaseCommand() {
    override fun execute() {
        val dbNodeCount = clusterState.hosts[ServerType.Cassandra]?.size ?: 0
        val appNodeCount = clusterState.hosts[ServerType.Stress]?.size ?: 0

        println(buildInfoText(dbNodeCount, appNodeCount, KitStorage.from(clusterState)))

        val exampleHelmValues =
            PlatformInfo::class.java
                .getResourceAsStream("example-helm-values.yaml")
                ?.bufferedReader()
                ?.readText()
                ?: error("Resource not found: example-helm-values.yaml")

        println("\nExample helm values:\n$exampleHelmValues")
    }

    companion object {
        fun buildInfoText(
            dbNodeCount: Int,
            appNodeCount: Int,
            kitStorage: KitStorage,
        ): String =
            """
            |Platform substrate info:
            |  Kit storage: ${describe(kitStorage)}
            |  DB nodes: $dbNodeCount  (nodeSelector: type=db)
            |  App nodes: $appNodeCount  (nodeSelector: type=app)
            |  Ordinal label: ${Constants.NODE_ORDINAL_LABEL}
            """.trimMargin()

        private fun describe(kitStorage: KitStorage): String =
            when (kitStorage) {
                is KitStorage.LocalVolumes ->
                    "local volumes, StorageClass ${Constants.K8s.KIT_LOCAL_CLASS_PREFIX}<kit>, " +
                        "default size ${kitStorage.defaultSize}"
                is KitStorage.Provisioned ->
                    "StorageClass ${kitStorage.storageClass}, default size ${kitStorage.defaultSize}"
            }
    }
}
