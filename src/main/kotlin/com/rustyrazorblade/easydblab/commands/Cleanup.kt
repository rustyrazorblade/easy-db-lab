package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.K8sService
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@RequireProfileSetup
@Command(
    name = "cleanup",
    description = ["Delete workload data from cluster nodes via a K8s Job per node"],
    mixinStandardHelpOptions = true,
)
class Cleanup : PicoBaseCommand() {
    @Parameters(index = "0", description = ["Workload name (e.g. clickhouse, presto)"])
    lateinit var workload: String

    @Option(
        names = ["--node-type"],
        description = ["Node pool to clean: db or app (default: db)"],
    )
    var nodeType: String = "db"

    private val k8sService: K8sService by inject()

    override fun execute() {
        require(nodeType == "db" || nodeType == "app") {
            "node-type must be 'db' or 'app', got: $nodeType"
        }

        val serverType = if (nodeType == "app") ServerType.Stress else ServerType.Cassandra
        val controlHost =
            clusterState.hosts[ServerType.Control]?.firstOrNull()
                ?: error("No control node found in cluster state.")
        val targetNodes = clusterState.hosts[serverType] ?: emptyList()

        for ((ordinal, _) in targetNodes.withIndex()) {
            val job = buildCleanupJob(ordinal)
            k8sService
                .createJob(controlHost, Constants.K8s.NAMESPACE, job)
                .getOrThrow()
            eventBus.emit(Event.Cleanup.JobCreated(workload = workload))
        }

        eventBus.emit(Event.Cleanup.Complete(workload = workload))
    }

    private fun buildCleanupJob(ordinal: Int) =
        JobBuilder()
            .withNewMetadata()
            .withName("cleanup-$workload-$ordinal")
            .withNamespace(Constants.K8s.NAMESPACE)
            .withLabels<String, String>(
                mapOf(
                    "app.kubernetes.io/name" to "cleanup",
                    "app.kubernetes.io/component" to workload,
                ),
            ).endMetadata()
            .withNewSpec()
            .withTtlSecondsAfterFinished(Constants.K8s.CLEANUP_JOB_TTL_SECONDS)
            .withNewTemplate()
            .withNewSpec()
            .withRestartPolicy("Never")
            .withNodeSelector<String, String>(
                mapOf(Constants.NODE_ORDINAL_LABEL to ordinal.toString()),
            ).withContainers(
                ContainerBuilder()
                    .withName("cleanup")
                    .withImage("busybox:latest")
                    .withCommand(
                        "sh",
                        "-c",
                        "rm -rf ${Constants.K8s.DB_MOUNT_PATH}/$workload && echo 'cleanup done'",
                    ).withVolumeMounts(
                        VolumeMountBuilder()
                            .withName("data")
                            .withMountPath(Constants.K8s.DB_MOUNT_PATH)
                            .build(),
                    ).build(),
            ).withVolumes(
                VolumeBuilder()
                    .withName("data")
                    .withHostPath(
                        HostPathVolumeSourceBuilder()
                            .withPath(Constants.K8s.DB_MOUNT_PATH)
                            .withType("Directory")
                            .build(),
                    ).build(),
            ).endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
