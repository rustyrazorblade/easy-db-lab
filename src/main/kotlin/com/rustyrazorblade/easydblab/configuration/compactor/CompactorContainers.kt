package com.rustyrazorblade.easydblab.configuration.compactor

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.compactorlease.GateFile
import com.rustyrazorblade.easydblab.compactorlease.LeaseAgentConfig
import com.rustyrazorblade.easydblab.compactorlease.LeaseTiming
import com.rustyrazorblade.easydblab.configuration.clusterConfigEnv
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.VolumeMount
import io.fabric8.kubernetes.api.model.VolumeMountBuilder

/**
 * The containers of the `account-compactor` pod: the busybox init container, the lease container,
 * and the four compactors, each wrapped by the gate.
 *
 * Every compactor runs its image's own binary with its compaction flags, under
 * `/gate/bin/busybox sh /config/compactor-gate.sh -- <binary> <args>`. The distroless images have
 * no shell, so the init container copies the static busybox into an emptyDir first. No container
 * has a limit or a request, and only the lease container has a readiness probe, so a standby pod is
 * Ready while its compactors wait.
 *
 * @property bucketRegion the account bucket's region; every container signs for it.
 */
internal class CompactorContainers(
    private val bucketRegion: String,
) {
    companion object {
        const val CONFIG_DIR = "/config"
        const val GATE_DIR = "/gate/bin"
        const val LEASE_DIR = "/lease"
        const val AGENT_DIR = "/agent"
        const val SCRATCH_DIR = "/scratch"
        const val GATE_BUSYBOX = "$GATE_DIR/busybox"

        /** Each Tempo process runs in one network namespace with the other, so the worker moves its ports. */
        private const val TEMPO_WORKER_HTTP_PORT = 3201
        private const val TEMPO_WORKER_GRPC_PORT = 9095
        private const val TEMPO_WORKER_GOSSIP_PORT = 7949

        /** Each image's own binary, its entrypoint. */
        private const val MIMIR_BINARY = "/bin/mimir"
        private const val LOKI_BINARY = "/usr/bin/loki"
        private const val TEMPO_BINARY = "/tempo"

        private const val READINESS_PERIOD_SECONDS = 5

        /** Mimir's compactor flags: compaction on, retention and partial-block deletion off. */
        val MIMIR_COMPACTOR_ARGS =
            listOf(
                "-target=compactor",
                // Rewrites every tenant's bucket index every minute, so readers see new blocks.
                "-compactor.cleanup-interval=1m",
                // Its default of 1d deletes partial blocks no merged copy replaces; 0 disables it.
                "-compactor.partial-block-deletion-delay=0",
                "-compactor.blocks-retention-period=0",
                // Merges the clusters' 1-minute blocks into 10-minute blocks first, every 5 minutes,
                // 2 minutes after they land (issue 988). Compaction only.
                "-compactor.block-ranges=10m,2h,12h,24h",
                "-compactor.compaction-interval=5m",
                "-compactor.first-level-compaction-wait-period=2m",
                "-compactor.data-dir=${Scratch.MIMIR.mountPath}/compactor",
                "-compactor.ring.store=inmemory",
                "-compactor.ring.instance-addr=127.0.0.1",
            )

        /**
         * Loki's compactor flags: compaction on, retention off. Deletion is `disabled` in `loki.yaml`.
         * Loki compacts every table, today's included, and keeps its working state under `/loki`.
         */
        val LOKI_COMPACTOR_ARGS =
            listOf(
                "-target=compactor",
                "-compactor.compaction-interval=10m",
                "-compactor.retention-enabled=false",
            )

        /** The lease timing, as the agent and the gates read it. */
        private val TIMING_ENV =
            mapOf(
                LeaseTiming.TTL_ENV to Constants.Compactor.Lease.TTL_SECONDS,
                LeaseTiming.RENEW_INTERVAL_ENV to Constants.Compactor.Lease.RENEW_INTERVAL_SECONDS,
                LeaseTiming.RENEW_DEADLINE_ENV to Constants.Compactor.Lease.RENEW_DEADLINE_SECONDS,
                LeaseTiming.STANDBY_POLL_ENV to Constants.Compactor.Lease.STANDBY_POLL_SECONDS,
                LeaseTiming.GATE_POLL_ENV to Constants.Compactor.Lease.GATE_POLL_SECONDS,
                LeaseTiming.GATE_GRACE_ENV to Constants.Compactor.Lease.GATE_GRACE_SECONDS,
            )

        private val GATE_TIMING_ENV = setOf(LeaseTiming.GATE_POLL_ENV, LeaseTiming.GATE_GRACE_ENV)
    }

    private fun env(
        name: String,
        value: String,
    ): EnvVar =
        EnvVarBuilder()
            .withName(name)
            .withValue(value)
            .build()

    private fun mount(
        volume: String,
        path: String,
        readOnly: Boolean = false,
    ): VolumeMount =
        VolumeMountBuilder()
            .withName(volume)
            .withMountPath(path)
            .withReadOnly(readOnly)
            .build()

    /** What every container reads: the bucket and the region it signs for. */
    private fun storageEnv(): List<EnvVar> =
        listOf(
            env("AWS_REGION", bucketRegion),
            clusterConfigEnv("S3_BUCKET", "s3_bucket"),
        )

    /** What the compactors' configuration files expand. */
    private fun compactorEnv(): List<EnvVar> =
        storageEnv() +
            listOf(
                env("METRICS_S3_PREFIX", Constants.Observability.METRICS_ROOT),
                env("LOGS_S3_PREFIX", Constants.Observability.LOGS_ROOT),
                env("TRACES_S3_PREFIX", Constants.Observability.TRACES_ROOT),
                // loki.yaml names its ingester after these; the compactor runs no ingester.
                env("TENANT", "compactor"),
                env("CLUSTER_NAME", "compactor"),
                env("GATE_BUSYBOX", GATE_BUSYBOX),
                env("GATE_VALID_UNTIL", "$LEASE_DIR/${GateFile.VALID_UNTIL}"),
            ) + TIMING_ENV.filterKeys { it in GATE_TIMING_ENV }.map { (name, value) -> env(name, value.toString()) }

    /** Copies the static busybox into the shared emptyDir, for the gates. */
    fun init(): Container =
        ContainerBuilder()
            .withName(CompactorManifestBuilder.GATE_INIT_CONTAINER)
            .withImage(Constants.Compactor.GATE_IMAGE)
            .withCommand("/bin/busybox", "cp", "/bin/busybox", GATE_BUSYBOX)
            .withVolumeMounts(mount(CompactorManifestBuilder.GATE_VOLUME, GATE_DIR))
            .build()

    /** The lease agent. Ready once it has read the lease, so a store or credential problem shows in `up`. */
    fun lease(): Container =
        ContainerBuilder()
            .withName(CompactorManifestBuilder.LEASE_CONTAINER)
            .withImage(Constants.Compactor.AGENT_IMAGE)
            .withCommand("java", "-jar", "$AGENT_DIR/${Constants.Compactor.AGENT_JAR}")
            .withEnv(
                storageEnv() +
                    listOf(
                        clusterConfigEnv(LeaseAgentConfig.CLUSTER_LABEL_ENV, "cluster_name"),
                        EnvVarBuilder()
                            .withName(LeaseAgentConfig.POD_NAME_ENV)
                            .withNewValueFrom()
                            .withNewFieldRef()
                            .withFieldPath("metadata.name")
                            .endFieldRef()
                            .endValueFrom()
                            .build(),
                        env(LeaseAgentConfig.LEASE_DIR_ENV, LEASE_DIR),
                        env(LeaseAgentConfig.SCRATCH_DIRS_ENV, Scratch.entries.joinToString(":") { "$SCRATCH_DIR/${it.dir}" }),
                    ) + TIMING_ENV.map { (name, value) -> env(name, value.toString()) },
            ).withVolumeMounts(
                listOf(
                    mount(CompactorManifestBuilder.LEASE_VOLUME, LEASE_DIR),
                    mount(CompactorManifestBuilder.AGENT_VOLUME, "$AGENT_DIR/${Constants.Compactor.AGENT_JAR}", readOnly = true),
                ) + Scratch.entries.map { mount(it.volume, "$SCRATCH_DIR/${it.dir}") },
            ).withNewReadinessProbe()
            .withNewExec()
            .withCommand("test", "-f", "$LEASE_DIR/${GateFile.READY}")
            .endExec()
            .withPeriodSeconds(READINESS_PERIOD_SECONDS)
            .endReadinessProbe()
            .build()

    /** A compactor under the gate: [binary] and [args] run only while the lease is valid. */
    private fun gated(
        name: String,
        image: String,
        binary: String,
        args: List<String>,
        scratch: Scratch,
    ): Container =
        ContainerBuilder()
            .withName(name)
            .withImage(image)
            .withCommand(GATE_BUSYBOX, "sh", "$CONFIG_DIR/${CompactorManifestBuilder.GATE_SCRIPT}", "--", binary)
            .withArgs(args)
            .withEnv(compactorEnv())
            .withVolumeMounts(
                mount(CompactorManifestBuilder.CONFIG_VOLUME, CONFIG_DIR, readOnly = true),
                mount(CompactorManifestBuilder.GATE_VOLUME, GATE_DIR, readOnly = true),
                mount(CompactorManifestBuilder.LEASE_VOLUME, LEASE_DIR, readOnly = true),
                mount(scratch.volume, scratch.mountPath),
            ).build()

    /** The four compactors. */
    fun compactors(): List<Container> {
        val tempoConfig = "-config.file=$CONFIG_DIR/${CompactorManifestBuilder.TEMPO_BACKEND_FILE}"
        return listOf(
            gated(
                CompactorManifestBuilder.MIMIR_CONTAINER,
                MimirManifestBuilder.IMAGE,
                MIMIR_BINARY,
                listOf("-config.file=$CONFIG_DIR/${MimirManifestBuilder.CONFIG_FILE}", "-config.expand-env=true") + MIMIR_COMPACTOR_ARGS,
                Scratch.MIMIR,
            ),
            gated(
                CompactorManifestBuilder.LOKI_CONTAINER,
                LokiManifestBuilder.IMAGE,
                LOKI_BINARY,
                listOf("-config.file=$CONFIG_DIR/${LokiManifestBuilder.CONFIG_FILE}", "-config.expand-env=true") + LOKI_COMPACTOR_ARGS,
                Scratch.LOKI,
            ),
            gated(
                CompactorManifestBuilder.TEMPO_SCHEDULER_CONTAINER,
                TempoManifestBuilder.IMAGE,
                TEMPO_BINARY,
                listOf(tempoConfig, "-config.expand-env=true", "-target=backend-scheduler"),
                Scratch.TEMPO,
            ),
            gated(
                CompactorManifestBuilder.TEMPO_WORKER_CONTAINER,
                TempoManifestBuilder.IMAGE,
                TEMPO_BINARY,
                listOf(
                    tempoConfig,
                    "-config.expand-env=true",
                    "-target=backend-worker",
                    "-server.http-listen-port=$TEMPO_WORKER_HTTP_PORT",
                    "-server.grpc-listen-port=$TEMPO_WORKER_GRPC_PORT",
                    "-memberlist.bind-port=$TEMPO_WORKER_GOSSIP_PORT",
                ),
                Scratch.TEMPO,
            ),
        )
    }
}
