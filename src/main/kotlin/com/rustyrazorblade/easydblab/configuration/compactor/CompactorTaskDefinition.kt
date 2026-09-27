package com.rustyrazorblade.easydblab.configuration.compactor

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import software.amazon.awssdk.core.SdkPojo
import software.amazon.awssdk.services.ecs.model.CPUArchitecture
import software.amazon.awssdk.services.ecs.model.Compatibility
import software.amazon.awssdk.services.ecs.model.ContainerCondition
import software.amazon.awssdk.services.ecs.model.ContainerDefinition
import software.amazon.awssdk.services.ecs.model.ContainerDependency
import software.amazon.awssdk.services.ecs.model.EphemeralStorage
import software.amazon.awssdk.services.ecs.model.KeyValuePair
import software.amazon.awssdk.services.ecs.model.LogConfiguration
import software.amazon.awssdk.services.ecs.model.LogDriver
import software.amazon.awssdk.services.ecs.model.MountPoint
import software.amazon.awssdk.services.ecs.model.NetworkMode
import software.amazon.awssdk.services.ecs.model.OSFamily
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest
import software.amazon.awssdk.services.ecs.model.RuntimePlatform
import software.amazon.awssdk.services.ecs.model.Tag
import software.amazon.awssdk.services.ecs.model.Volume
import java.security.MessageDigest
import java.util.Base64

/**
 * Builds the account compactor's ECS task definition: a pure function of the bucket, its region,
 * the two roles and the log group.
 *
 * One Fargate task runs every compactor of the shared store, each in its own container on its own
 * ports: Mimir's compactor, Loki's compactor, and Tempo's backend-scheduler and backend-worker (Tempo
 * 3.0.3 runs one target per process). A small busybox container runs first and writes their
 * configuration files, carried base64-encoded in its environment, to a volume the others read.
 * Mimir and Loki reuse the cluster's own `mimir.yaml` and `loki.yaml` with flag overrides, so the
 * compactors always read the store the way the clusters write it.
 *
 * Compaction is on; retention and every other deletion path are off. The configuration files are
 * read from the classpath, not through `TemplateService`, because the compactor commands run
 * outside a cluster workspace.
 *
 * @property bucket the account bucket.
 * @property region the account bucket's region.
 * @property taskRoleArn the role the containers run as (`EasyDBLabCompactorTaskRole`).
 * @property executionRoleArn the role ECS pulls images and ships logs with.
 * @property logGroup the CloudWatch Logs group every container logs to.
 */
data class CompactorTaskDefinition(
    val bucket: String,
    val region: String,
    val taskRoleArn: String,
    val executionRoleArn: String,
    val logGroup: String = Constants.Compactor.LOG_GROUP,
) {
    companion object {
        const val CONFIG_CONTAINER = "config"
        const val MIMIR_CONTAINER = "mimir-compactor"
        const val LOKI_CONTAINER = "loki-compactor"
        const val TEMPO_SCHEDULER_CONTAINER = "tempo-backend-scheduler"
        const val TEMPO_WORKER_CONTAINER = "tempo-backend-worker"

        /** Every container, in the order the task definition lists them. */
        val CONTAINERS =
            listOf(CONFIG_CONTAINER, MIMIR_CONTAINER, LOKI_CONTAINER, TEMPO_SCHEDULER_CONTAINER, TEMPO_WORKER_CONTAINER)

        const val TEMPO_BACKEND_FILE = "tempo-backend.yaml"
        private const val CONFIG_DIR = "/config"
        private const val CONFIG_VOLUME = "config"
        private const val ROOT_USER = "0"

        /** The Tempo worker's ports; the scheduler keeps the ones in `tempo-backend.yaml`. */
        private const val TEMPO_WORKER_HTTP_PORT = 3201
        private const val TEMPO_WORKER_GRPC_PORT = 9095
        private const val TEMPO_WORKER_GOSSIP_PORT = 7949

        /** Mimir's compactor flags: compaction on, retention and partial-block deletion off. */
        val MIMIR_COMPACTOR_ARGS =
            listOf(
                "-target=compactor",
                // Rewrites every tenant's bucket index every minute, so readers see new blocks.
                "-compactor.cleanup-interval=1m",
                // Its default of 1d deletes partial blocks no merged copy replaces; 0 disables it.
                "-compactor.partial-block-deletion-delay=0",
                "-compactor.blocks-retention-period=0",
                "-compactor.data-dir=/data/compactor",
                "-compactor.ring.store=inmemory",
                "-compactor.ring.instance-addr=127.0.0.1",
            )

        /** Loki's compactor flags: compaction on, retention off. Deletion is `disabled` in `loki.yaml`. */
        val LOKI_COMPACTOR_ARGS =
            listOf(
                "-target=compactor",
                "-compactor.compaction-interval=10m",
                "-compactor.retention-enabled=false",
            )

        /** Reads a configuration file from the classpath, next to [anchor]. */
        fun resource(
            anchor: Class<*>,
            name: String,
        ): String =
            checkNotNull(anchor.getResourceAsStream(name)) { "Missing classpath resource $name next to ${anchor.simpleName}" }
                .bufferedReader()
                .use { it.readText() }

        /** The `awslogs-stream-prefix` of [container]; awslogs names its stream `<prefix>/<container>/<task id>`. */
        fun logStreamPrefix(container: String): String = container

        /** The CloudWatch Logs stream of [container] in task [taskId]. */
        fun logStream(
            container: String,
            taskId: String,
        ): String = "${logStreamPrefix(container)}/$container/$taskId"

        /** A SHA-256 over every field of [request], nested ones included, in the SDK's field order. */
        fun hashOf(request: RegisterTaskDefinitionRequest): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(canonical(request).toByteArray())
                .joinToString("") { "%02x".format(it) }

        /** Spells out every field: the SDK's `toString` redacts some. Map keys are sorted. */
        private fun canonical(value: Any?): String =
            when (value) {
                is SdkPojo ->
                    value.sdkFields().joinToString(
                        ",",
                        "{",
                        "}",
                    ) { "${it.memberName()}=${canonical(it.getValueOrDefault(value))}" }
                is Map<*, *> ->
                    value.entries.sortedBy { it.key.toString() }.joinToString(
                        ",",
                        "{",
                        "}",
                    ) { "${it.key}=${canonical(it.value)}" }
                is Collection<*> -> value.joinToString(",", "[", "]") { canonical(it) }
                else -> value.toString()
            }

        /** Every configuration file the task writes, by file name. */
        fun configFiles(): Map<String, String> =
            mapOf(
                MimirManifestBuilder.CONFIG_FILE to resource(MimirManifestBuilder::class.java, MimirManifestBuilder.CONFIG_FILE),
                LokiManifestBuilder.CONFIG_FILE to resource(LokiManifestBuilder::class.java, LokiManifestBuilder.CONFIG_FILE),
                TEMPO_BACKEND_FILE to resource(CompactorTaskDefinition::class.java, TEMPO_BACKEND_FILE),
            )
    }

    /** What the containers read from their environment; the configuration files expand it. */
    private fun storageEnv(): List<KeyValuePair> =
        mapOf(
            "S3_BUCKET" to bucket,
            "AWS_REGION" to region,
            "METRICS_S3_PREFIX" to Constants.Observability.METRICS_ROOT,
            "LOGS_S3_PREFIX" to Constants.Observability.LOGS_ROOT,
            "TRACES_S3_PREFIX" to Constants.Observability.TRACES_ROOT,
            // loki.yaml names its ingester after these; the compactor runs no ingester.
            "TENANT" to "compactor",
            "CLUSTER_NAME" to "compactor",
        ).map { (name, value) ->
            KeyValuePair
                .builder()
                .name(name)
                .value(value)
                .build()
        }

    private fun logs(container: String): LogConfiguration =
        LogConfiguration
            .builder()
            .logDriver(LogDriver.AWSLOGS)
            .options(
                mapOf(
                    "awslogs-group" to logGroup,
                    "awslogs-region" to region,
                    "awslogs-stream-prefix" to logStreamPrefix(container),
                ),
            ).build()

    /** A compactor container: reads the configuration volume, starts once the config container has exited cleanly. */
    private fun compactor(
        name: String,
        image: String,
        args: List<String>,
        user: String? = null,
    ): ContainerDefinition =
        ContainerDefinition
            .builder()
            .name(name)
            .image(image)
            .essential(true)
            .command(args)
            .user(user)
            .environment(storageEnv())
            .mountPoints(
                MountPoint
                    .builder()
                    .sourceVolume(CONFIG_VOLUME)
                    .containerPath(CONFIG_DIR)
                    .readOnly(true)
                    .build(),
            ).dependsOn(
                ContainerDependency
                    .builder()
                    .containerName(CONFIG_CONTAINER)
                    .condition(ContainerCondition.SUCCESS)
                    .build(),
            ).stopTimeout(Constants.Compactor.STOP_TIMEOUT_SECONDS)
            .logConfiguration(logs(name))
            .build()

    /** The busybox container that writes every configuration file, carried base64-encoded in its environment. */
    private fun configWriter(): ContainerDefinition {
        val encoded = configFiles().mapValues { Base64.getEncoder().encodeToString(it.value.toByteArray()) }
        val script =
            encoded.keys.withIndex().joinToString(" && ") { (index, file) ->
                "echo \"\$CONFIG_$index\" | base64 -d > $CONFIG_DIR/$file"
            }
        return ContainerDefinition
            .builder()
            .name(CONFIG_CONTAINER)
            .image(Constants.Compactor.CONFIG_IMAGE)
            .essential(false)
            .command("sh", "-c", script)
            .environment(
                encoded.values.withIndex().map { (index, value) ->
                    KeyValuePair
                        .builder()
                        .name("CONFIG_$index")
                        .value(value)
                        .build()
                },
            ).mountPoints(
                MountPoint
                    .builder()
                    .sourceVolume(CONFIG_VOLUME)
                    .containerPath(CONFIG_DIR)
                    .build(),
            ).logConfiguration(logs(CONFIG_CONTAINER))
            .build()
    }

    /** The containers, the config writer first. */
    fun containers(): List<ContainerDefinition> {
        val tempoConfig = "-config.file=$CONFIG_DIR/$TEMPO_BACKEND_FILE"
        return listOf(
            configWriter(),
            compactor(
                MIMIR_CONTAINER,
                MimirManifestBuilder.IMAGE,
                listOf("-config.file=$CONFIG_DIR/${MimirManifestBuilder.CONFIG_FILE}", "-config.expand-env=true") + MIMIR_COMPACTOR_ARGS,
            ),
            compactor(
                LOKI_CONTAINER,
                LokiManifestBuilder.IMAGE,
                listOf("-config.file=$CONFIG_DIR/${LokiManifestBuilder.CONFIG_FILE}", "-config.expand-env=true") + LOKI_COMPACTOR_ARGS,
                user = ROOT_USER,
            ),
            compactor(
                TEMPO_SCHEDULER_CONTAINER,
                TempoManifestBuilder.IMAGE,
                listOf(tempoConfig, "-config.expand-env=true", "-target=backend-scheduler"),
                user = ROOT_USER,
            ),
            compactor(
                TEMPO_WORKER_CONTAINER,
                TempoManifestBuilder.IMAGE,
                listOf(
                    tempoConfig,
                    "-config.expand-env=true",
                    "-target=backend-worker",
                    "-server.http-listen-port=$TEMPO_WORKER_HTTP_PORT",
                    "-server.grpc-listen-port=$TEMPO_WORKER_GRPC_PORT",
                    "-memberlist.bind-port=$TEMPO_WORKER_GOSSIP_PORT",
                ),
                user = ROOT_USER,
            ),
        )
    }

    /**
     * A hash of every field of the `RegisterTaskDefinition` request except its tags. A service
     * started at desired count 0 registers a new revision only when this differs from the latest
     * revision's [Constants.Compactor.CONFIG_HASH_TAG].
     */
    fun configHash(): String = hashOf(untaggedRequest())

    /** The `RegisterTaskDefinition` request, tagged with [configHash]. */
    fun request(): RegisterTaskDefinitionRequest {
        val untagged = untaggedRequest()
        return untagged
            .toBuilder()
            .tags(
                Tag
                    .builder()
                    .key(Constants.Compactor.CONFIG_HASH_TAG)
                    .value(hashOf(untagged))
                    .build(),
            ).build()
    }

    /** The request without tags: everything the task runs with. */
    fun untaggedRequest(): RegisterTaskDefinitionRequest =
        RegisterTaskDefinitionRequest
            .builder()
            .family(Constants.Compactor.TASK_FAMILY)
            .requiresCompatibilities(Compatibility.FARGATE)
            .networkMode(NetworkMode.AWSVPC)
            .runtimePlatform(
                RuntimePlatform
                    .builder()
                    .cpuArchitecture(CPUArchitecture.ARM64)
                    .operatingSystemFamily(OSFamily.LINUX)
                    .build(),
            ).cpu(Constants.Compactor.CPU_UNITS)
            .memory(Constants.Compactor.MEMORY_MIB)
            .ephemeralStorage(EphemeralStorage.builder().sizeInGiB(Constants.Compactor.EPHEMERAL_STORAGE_GIB).build())
            .taskRoleArn(taskRoleArn)
            .executionRoleArn(executionRoleArn)
            .volumes(Volume.builder().name(CONFIG_VOLUME).build())
            .containerDefinitions(containers())
            .build()
}
