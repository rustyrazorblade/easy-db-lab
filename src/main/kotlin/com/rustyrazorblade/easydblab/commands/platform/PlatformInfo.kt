package com.rustyrazorblade.easydblab.commands.platform

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import picocli.CommandLine.Command

@RequireProfileSetup
@Command(
    name = "info",
    description = ["Show K8s platform substrate details: StorageClasses, node pools, selectors"],
)
class PlatformInfo : PicoBaseCommand() {
    override fun execute() {
        val dbNodeCount = clusterState.hosts[ServerType.Cassandra]?.size ?: 0
        val appNodeCount = clusterState.hosts[ServerType.Stress]?.size ?: 0

        val exampleHelmValues =
            PlatformInfo::class.java
                .getResourceAsStream("example-helm-values.yaml")
                ?.bufferedReader()
                ?.readText()
                ?: error("Resource not found: example-helm-values.yaml")

        eventBus.emit(
            Event.Platform.Info(
                storageClasses = listOf(Constants.K8s.LOCAL_STORAGE_CLASS, Constants.K8s.LOCAL_STORAGE_WFC_CLASS),
                dbNodeCount = dbNodeCount,
                appNodeCount = appNodeCount,
                nodeOrdinalLabel = Constants.NODE_ORDINAL_LABEL,
                exampleHelmValues = exampleHelmValues,
            ),
        )
    }
}
