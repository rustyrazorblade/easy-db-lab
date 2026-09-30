package com.rustyrazorblade.easydblab.configuration.compactor

import com.rustyrazorblade.easydblab.YamlTestSupport.keysAt
import com.rustyrazorblade.easydblab.YamlTestSupport.scalarAt
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.ecs.model.CPUArchitecture
import software.amazon.awssdk.services.ecs.model.ContainerDefinition
import software.amazon.awssdk.services.ecs.model.LogDriver
import software.amazon.awssdk.services.ecs.model.NetworkMode
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest
import software.amazon.awssdk.services.ecs.model.RuntimePlatform
import software.amazon.awssdk.services.ecs.model.Volume
import java.util.Base64

/**
 * Locks the account compactor's retention and deletion settings. The compactor is the only
 * process allowed to delete under `mimir/`, `loki/` and `tempo/`, so every flag and setting that
 * would let it delete data it did not merge is asserted here, on the task definition ECS runs and
 * on the configuration files the task writes.
 */
class CompactorTaskDefinitionTest {
    private val definition =
        CompactorTaskDefinition(
            bucket = "easy-db-lab-acct",
            region = "eu-west-1",
            taskRoleArn = "arn:aws:iam::1:role/EasyDBLabCompactorTaskRole",
            executionRoleArn = "arn:aws:iam::1:role/EasyDBLabCompactorExecutionRole",
        )
    private val request = definition.request()
    private val containers = request.containerDefinitions().associateBy { it.name() }

    private fun command(container: String): List<String> = containers.getValue(container).command()

    /** The configuration file the config container writes, decoded from its environment. */
    private fun writtenConfig(file: String): String {
        val config = containers.getValue(CompactorTaskDefinition.CONFIG_CONTAINER)
        val script = config.command().last()
        val match = checkNotNull(Regex("""echo "\$(CONFIG_\d+)" \| base64 -d > /config/${Regex.escape(file)}""").find(script)) { script }
        val variable = match.groupValues[1]
        val encoded = config.environment().single { it.name() == variable }.value()
        return String(Base64.getDecoder().decode(encoded))
    }

    @Test
    fun `Mimir compacts with retention and partial-block deletion off, and rewrites every bucket index each minute`() {
        assertThat(command(CompactorTaskDefinition.MIMIR_CONTAINER)).contains(
            "-target=compactor",
            "-compactor.blocks-retention-period=0",
            "-compactor.partial-block-deletion-delay=0",
            "-compactor.cleanup-interval=1m",
        )
    }

    /**
     * The 1-minute blocks every cluster ships are merged into 10-minute blocks first, every 5
     * minutes, 2 minutes after they land, so a store-gateway read opens far fewer tiny blocks
     * (issue 988).
     */
    @Test
    fun `Mimir merges one-minute blocks into ten-minute blocks every five minutes`() {
        assertThat(command(CompactorTaskDefinition.MIMIR_CONTAINER)).contains(
            "-compactor.block-ranges=10m,2h,12h,24h",
            "-compactor.compaction-interval=5m",
            "-compactor.first-level-compaction-wait-period=2m",
        )
    }

    @Test
    fun `Loki compacts with retention off and deletion disabled`() {
        assertThat(command(CompactorTaskDefinition.LOKI_CONTAINER))
            .contains("-target=compactor", "-compactor.retention-enabled=false")
            .noneMatch { it.startsWith("-compactor.retention-enabled=true") || it.startsWith("-deletion-mode") }
        val loki = writtenConfig(LokiManifestBuilder.CONFIG_FILE)
        assertThat(scalarAt(loki, "limits_config", "deletion_mode")).isEqualTo("disabled")
        assertThat(scalarAt(loki, "compactor", "retention_enabled")).isEqualTo("false")
    }

    @Test
    fun `Tempo keeps every block, every span of a large trace and every empty tenant, with no per-tenant override`() {
        val tempo = writtenConfig(CompactorTaskDefinition.TEMPO_BACKEND_FILE)

        assertThat(scalarAt(tempo, "backend_worker", "compaction", "block_retention")).isEqualTo("876000h")
        assertThat(scalarAt(tempo, "overrides", "defaults", "global", "max_bytes_per_trace")).isEqualTo("0")
        assertThat(scalarAt(tempo, "storage", "trace", "empty_tenant_deletion_enabled")).isEqualTo("false")
        // No per-tenant retention: the defaults hold only the trace-size limit.
        assertThat(keysAt(tempo, "overrides")).containsExactly("defaults")
        assertThat(keysAt(tempo, "overrides", "defaults")).containsExactly("global")
        assertThat(tempo).doesNotContain("per_tenant_override")
    }

    @Test
    fun `the task runs Mimir's and Loki's compactors and Tempo's scheduler and worker, and no Pyroscope`() {
        assertThat(containers.keys).containsExactlyInAnyOrderElementsOf(CompactorTaskDefinition.CONTAINERS)
        assertThat(command(CompactorTaskDefinition.TEMPO_SCHEDULER_CONTAINER)).contains("-target=backend-scheduler")
        assertThat(command(CompactorTaskDefinition.TEMPO_WORKER_CONTAINER)).contains("-target=backend-worker")
        assertThat(containers.values.map { it.image() }).noneMatch { it.contains("pyroscope") }
    }

    @Test
    fun `the configs are written before any compactor starts, and each compactor reads the bucket and roots from its environment`() {
        val compactors = containers - CompactorTaskDefinition.CONFIG_CONTAINER

        assertThat(containers.getValue(CompactorTaskDefinition.CONFIG_CONTAINER).essential()).isFalse()
        assertThat(compactors.values).allSatisfy { container ->
            assertThat(container.dependsOn().single().containerName()).isEqualTo(CompactorTaskDefinition.CONFIG_CONTAINER)
            assertThat(container.environment().associate { it.name() to it.value() })
                .containsEntry("S3_BUCKET", "easy-db-lab-acct")
                .containsEntry("AWS_REGION", "eu-west-1")
                .containsEntry("METRICS_S3_PREFIX", "mimir")
                .containsEntry("LOGS_S3_PREFIX", "loki")
                .containsEntry("TRACES_S3_PREFIX", "tempo")
            assertThat(container.logConfiguration().logDriver()).isEqualTo(LogDriver.AWSLOGS)
            assertThat(container.logConfiguration().options()).containsEntry("awslogs-group", "/easy-db-lab/compactor")
        }
        // Tempo's two processes run side by side in one network namespace, on different ports.
        assertThat(command(CompactorTaskDefinition.TEMPO_WORKER_CONTAINER)).contains("-server.http-listen-port=3201")
    }

    @Test
    fun `the task is an ARM64 Fargate task of 2 vCPU, 8 GiB and 100 GiB, tagged with its config hash`() {
        assertThat(request.networkMode()).isEqualTo(NetworkMode.AWSVPC)
        assertThat(request.runtimePlatform().cpuArchitecture()).isEqualTo(CPUArchitecture.ARM64)
        assertThat(request.cpu()).isEqualTo("2048")
        assertThat(request.memory()).isEqualTo("8192")
        assertThat(request.ephemeralStorage().sizeInGiB()).isEqualTo(100)
        assertThat(request.tags().single().value()).isEqualTo(definition.configHash())
    }

    @Test
    fun `the config hash follows the configuration and nothing else`() {
        val same = definition.copy()
        val otherBucket = definition.copy(bucket = "easy-db-lab-other")

        assertThat(same.configHash()).isEqualTo(definition.configHash())
        assertThat(otherBucket.configHash()).isNotEqualTo(definition.configHash())
    }

    /**
     * `mimir.yaml` writes its activity file and TSDB under `/data`, which is a hostPath on a
     * cluster. Without a volume there the compactor exits at start with "no such file or directory".
     */
    @Test
    fun `the Mimir compactor has a writable task volume at the data directory mimir yaml names`() {
        val mimir = containers.getValue(CompactorTaskDefinition.MIMIR_CONTAINER)
        val data = mimir.mountPoints().single { it.containerPath() == "/data" }

        assertThat(data.readOnly() == true).describedAs("read-only").isFalse()
        assertThat(request.volumes().map { it.name() }).contains(data.sourceVolume())
        assertThat(CompactorTaskDefinition.configFiles().getValue("mimir.yaml")).contains("filepath: /data/")
    }

    /** A stopped service reuses the latest revision only when nothing the task runs with changed. */
    @Test
    fun `the config hash covers every field of the task definition, and only the tag is left out`() {
        val untagged = definition.untaggedRequest()
        val hash = CompactorTaskDefinition.hashOf(untagged)

        fun changedContainer(change: (ContainerDefinition.Builder) -> Unit): RegisterTaskDefinitionRequest {
            val mimir = untagged.containerDefinitions().single { it.name() == CompactorTaskDefinition.MIMIR_CONTAINER }
            val changed = mimir.toBuilder().also(change).build()
            return untagged
                .toBuilder()
                .containerDefinitions(untagged.containerDefinitions().map { if (it == mimir) changed else it })
                .build()
        }
        val variants =
            mapOf(
                "essential" to changedContainer { it.essential(false) },
                "dependsOn" to changedContainer { it.dependsOn(emptyList()) },
                "mountPoints" to changedContainer { it.mountPoints(emptyList()) },
                "stopTimeout" to changedContainer { it.stopTimeout(1) },
                "volumes" to untagged.toBuilder().volumes(Volume.builder().name("other").build()).build(),
                "networkMode" to untagged.toBuilder().networkMode(NetworkMode.BRIDGE).build(),
                "runtimePlatform" to
                    untagged
                        .toBuilder()
                        .runtimePlatform(RuntimePlatform.builder().cpuArchitecture(CPUArchitecture.X86_64).build())
                        .build(),
            )

        assertThat(variants).allSatisfy { field, changed ->
            assertThat(CompactorTaskDefinition.hashOf(changed)).describedAs(field).isNotEqualTo(hash)
        }
        assertThat(definition.configHash()).isEqualTo(hash)
        assertThat(CompactorTaskDefinition.hashOf(request)).isNotEqualTo(hash)
    }
}
