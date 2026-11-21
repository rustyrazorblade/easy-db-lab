package com.rustyrazorblade.easydblab.configuration.compactor

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.YamlTestSupport.keysAt
import com.rustyrazorblade.easydblab.YamlTestSupport.scalarAt
import com.rustyrazorblade.easydblab.compactorlease.LeaseAgentConfig
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.store.S3Backend
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.apps.Deployment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The `account-compactor` Deployment and its configuration.
 *
 * It locks the compactor's retention and deletion settings:
 * the compactor removes source objects under `mimir/`, `loki/` and `tempo/`, so every flag that would
 * let it delete data it did not merge is asserted on the Deployment that runs it. It also locks the
 * pod shape the lease and the gate depend on.
 */
class CompactorManifestBuilderTest : BaseKoinTest() {
    private companion object {
        const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val BUCKET_REGION = "eu-west-1"
        val STORE = S3Backend(bucket = "acct", region = BUCKET_REGION)
    }

    private lateinit var builder: CompactorManifestBuilder

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "test", s3Bucket = "easy-db-lab-test"))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        builder = CompactorManifestBuilder(getKoin().get())
    }

    private val resources by lazy { builder.buildAllResources(TestScopes.EC2, CompactorAgentJar(SHA), STORE) }
    private val deployment by lazy { resources.filterIsInstance<Deployment>().single() }
    private val configMap by lazy { resources.filterIsInstance<ConfigMap>().single() }
    private val pod by lazy { deployment.spec.template.spec }

    private fun container(name: String): Container = pod.containers.single { it.name == name }

    private fun file(name: String): String = configMap.data.getValue(name)

    /** The binary and arguments the gate runs for [name]: everything after `--`. */
    private fun gated(name: String): List<String> {
        val command = container(name).command + container(name).args.orEmpty()
        return command.drop(command.indexOf("--") + 1)
    }

    private fun env(container: Container): Map<String, String?> = container.env.associate { it.name to it.value }

    @Test
    fun `Mimir compacts with retention and partial-block deletion off, and rewrites every bucket index each minute`() {
        assertThat(gated(CompactorManifestBuilder.MIMIR_CONTAINER)).contains(
            "-target=compactor",
            "-compactor.blocks-retention-period=0",
            "-compactor.partial-block-deletion-delay=0",
            "-compactor.cleanup-interval=1m",
        )
    }

    @Test
    fun `Mimir merges one-minute blocks into ten-minute blocks every five minutes`() {
        assertThat(gated(CompactorManifestBuilder.MIMIR_CONTAINER)).contains(
            "-compactor.block-ranges=10m,2h,12h,24h",
            "-compactor.compaction-interval=5m",
            "-compactor.first-level-compaction-wait-period=2m",
        )
    }

    @Test
    fun `Loki compacts every table with retention off and deletion disabled`() {
        assertThat(gated(CompactorManifestBuilder.LOKI_CONTAINER))
            .contains("-target=compactor", "-compactor.retention-enabled=false")
            .noneMatch { it.startsWith("-compactor.retention-enabled=true") || it.startsWith("-deletion-mode") }
        val loki = file(LokiManifestBuilder.CONFIG_FILE)
        assertThat(scalarAt(loki, "limits_config", "deletion_mode")).isEqualTo("disabled")
        assertThat(scalarAt(loki, "compactor", "retention_enabled")).isEqualTo("false")
    }

    @Test
    fun `Tempo keeps every block, every span of a large trace and every empty tenant, with no per-tenant override`() {
        val tempo = file(CompactorManifestBuilder.TEMPO_BACKEND_FILE)

        assertThat(scalarAt(tempo, "backend_worker", "compaction", "block_retention")).isEqualTo("876000h")
        assertThat(scalarAt(tempo, "overrides", "defaults", "global", "max_bytes_per_trace")).isEqualTo("0")
        assertThat(scalarAt(tempo, "storage", "trace", "empty_tenant_deletion_enabled")).isEqualTo("false")
        assertThat(keysAt(tempo, "overrides")).containsExactly("defaults")
        assertThat(keysAt(tempo, "overrides", "defaults")).containsExactly("global")
        assertThat(tempo).doesNotContain("per_tenant_override")
    }

    @Test
    fun `the compactors read the cluster's own Mimir and Loki configuration`() {
        assertThat(
            file(MimirManifestBuilder.CONFIG_FILE),
        ).isEqualTo(MimirManifestBuilder(getKoin().get()).buildConfigMap(TestScopes.EC2).data.getValue("mimir.yaml"))
        assertThat(
            file(LokiManifestBuilder.CONFIG_FILE),
        ).isEqualTo(LokiManifestBuilder(getKoin().get()).buildConfigMap(TestScopes.EC2).data.getValue("loki.yaml"))
    }

    @Test
    fun `the pod runs the lease container and the four gated compactors, and no Pyroscope`() {
        assertThat(pod.containers.map { it.name }).containsExactly(
            CompactorManifestBuilder.LEASE_CONTAINER,
            CompactorManifestBuilder.MIMIR_CONTAINER,
            CompactorManifestBuilder.LOKI_CONTAINER,
            CompactorManifestBuilder.TEMPO_SCHEDULER_CONTAINER,
            CompactorManifestBuilder.TEMPO_WORKER_CONTAINER,
        )
        assertThat(pod.containers.map { it.image }).noneMatch { it.contains("pyroscope") }
        assertThat(gated(CompactorManifestBuilder.TEMPO_SCHEDULER_CONTAINER)).contains("-target=backend-scheduler")
        assertThat(
            gated(CompactorManifestBuilder.TEMPO_WORKER_CONTAINER),
        ).contains("-target=backend-worker", "-server.http-listen-port=3201")
    }

    @Test
    fun `every compactor runs its image's binary under the gate, run by the static busybox the init container copies`() {
        val init = pod.initContainers.single()
        assertThat(init.image).isEqualTo("busybox:1.37.0-musl")
        assertThat(init.command.joinToString(" ")).contains("/bin/busybox").contains("/gate/bin/busybox")

        val binaries =
            mapOf(
                CompactorManifestBuilder.MIMIR_CONTAINER to (MimirManifestBuilder.IMAGE to "/bin/mimir"),
                CompactorManifestBuilder.LOKI_CONTAINER to (LokiManifestBuilder.IMAGE to "/usr/bin/loki"),
                CompactorManifestBuilder.TEMPO_SCHEDULER_CONTAINER to (TempoManifestBuilder.IMAGE to "/tempo"),
                CompactorManifestBuilder.TEMPO_WORKER_CONTAINER to (TempoManifestBuilder.IMAGE to "/tempo"),
            )
        assertThat(binaries).allSatisfy { name, (image, binary) ->
            val compactor = container(name)
            assertThat(compactor.image).isEqualTo(image)
            assertThat(compactor.command.take(3)).containsExactly("/gate/bin/busybox", "sh", "/config/compactor-gate.sh")
            assertThat(gated(name).first()).isEqualTo(binary)
            assertThat(compactor.volumeMounts.map { it.mountPath }).contains("/gate/bin", "/lease", "/config")
        }
        assertThat(file(CompactorManifestBuilder.GATE_SCRIPT)).contains("compactor-gate")
    }

    @Test
    fun `the gates and the agent get the lease timing from Constants`() {
        val timing =
            mapOf(
                "LEASE_GATE_POLL_SECONDS" to
                    Constants.Compactor.Lease.GATE_POLL_SECONDS
                        .toString(),
                "LEASE_GATE_GRACE_SECONDS" to
                    Constants.Compactor.Lease.GATE_GRACE_SECONDS
                        .toString(),
            )
        assertThat(pod.containers.drop(1)).allSatisfy { assertThat(env(it)).containsAllEntriesOf(timing) }
        assertThat(env(container(CompactorManifestBuilder.LEASE_CONTAINER))).containsAllEntriesOf(
            timing +
                mapOf(
                    "LEASE_TTL_SECONDS" to "120",
                    "LEASE_RENEW_INTERVAL_SECONDS" to "15",
                    "LEASE_RENEW_DEADLINE_SECONDS" to "60",
                    "LEASE_STANDBY_POLL_SECONDS" to "15",
                ),
        )
    }

    @Test
    fun `the pod is on the pod network, one replica, Recreate, with a termination grace longer than the gate's stop`() {
        assertThat(listOf(pod.hostNetwork)).doesNotContain(true)
        assertThat(deployment.spec.replicas).isEqualTo(1)
        assertThat(deployment.spec.strategy.type).isEqualTo("Recreate")
        assertThat(pod.terminationGracePeriodSeconds).isEqualTo(60L)
        assertThat(pod.terminationGracePeriodSeconds)
            .isGreaterThan(Constants.Compactor.Lease.GATE_POLL_SECONDS + Constants.Compactor.Lease.GATE_GRACE_SECONDS)
        assertThat(pod.nodeSelector).containsEntry("node-role.kubernetes.io/control-plane", "true")
    }

    @Test
    fun `no container has a limit or a request`() {
        assertThat(pod.containers + pod.initContainers).allSatisfy {
            assertThat(
                it.resources?.limits.orEmpty() + it.resources?.requests.orEmpty(),
            ).isEmpty()
        }
    }

    @Test
    fun `only the lease container has a readiness probe, so a standby is Ready`() {
        assertThat(pod.containers.drop(1)).allSatisfy { assertThat(it.readinessProbe).isNull() }
        val probe = container(CompactorManifestBuilder.LEASE_CONTAINER).readinessProbe
        assertThat(probe.exec.command).contains("/lease/ready")
    }

    @Test
    fun `the lease container runs the agent jar from its SHA-256 path on the control node, so a new jar rolls the pod`() {
        val lease = container(CompactorManifestBuilder.LEASE_CONTAINER)
        val agentVolume = pod.volumes.single { it.name == CompactorManifestBuilder.AGENT_VOLUME }

        assertThat(agentVolume.hostPath.path).isEqualTo("/mnt/db1/compactor/agent/$SHA.jar")
        assertThat(agentVolume.hostPath.type).isEqualTo("File")
        assertThat(lease.command + lease.args.orEmpty()).contains("-jar", "/agent/compactor-lease.jar")
        assertThat(lease.image).isEqualTo(Constants.Compactor.AGENT_IMAGE)

        val other =
            builder
                .buildAllResources(
                    TestScopes.EC2,
                    CompactorAgentJar(SHA.reversed()),
                    STORE,
                ).filterIsInstance<Deployment>()
                .single()
        assertThat(other.spec.template).isNotEqualTo(deployment.spec.template)
    }

    @Test
    fun `scratch directories are host paths on the data mount, owned by the user each compactor runs as`() {
        val scratch = pod.volumes.filter { it.hostPath != null }.associate { it.name to it.hostPath.path }
        assertThat(scratch).containsAllEntriesOf(
            mapOf(
                "mimir-scratch" to "/mnt/db1/compactor/mimir",
                "loki-scratch" to "/mnt/db1/compactor/loki",
                "tempo-scratch" to "/mnt/db1/compactor/tempo",
            ),
        )
        assertThat(container(CompactorManifestBuilder.MIMIR_CONTAINER).volumeMounts.map { it.mountPath }).contains("/data")
        assertThat(container(CompactorManifestBuilder.LOKI_CONTAINER).volumeMounts.map { it.mountPath }).contains("/loki")
        assertThat(container(CompactorManifestBuilder.TEMPO_WORKER_CONTAINER).volumeMounts.map { it.mountPath }).contains("/var/tempo")
        assertThat(env(container(CompactorManifestBuilder.LEASE_CONTAINER))["SCRATCH_DIRS"])
            .isEqualTo("/scratch/mimir:/scratch/loki:/scratch/tempo")
        // The images' own users; the installer chowns the Loki and Tempo directories to 10001.
        assertThat(container(CompactorManifestBuilder.LOKI_CONTAINER).securityContext?.runAsUser).isNull()
        assertThat(container(CompactorManifestBuilder.TEMPO_WORKER_CONTAINER).securityContext?.runAsUser).isNull()
    }

    @Test
    fun `every container signs for the store's region and reads the store bucket and the store-prefixed roots`() {
        fun configKey(
            container: Container,
            name: String,
        ): String =
            container.env
                .single { it.name == name }
                .valueFrom.configMapKeyRef.key

        assertThat(pod.containers).allSatisfy { container ->
            // The lease agent's own names for the store, set on every container.
            assertThat(env(container)).containsEntry(LeaseAgentConfig.REGION_ENV, BUCKET_REGION)
            assertThat(configKey(container, LeaseAgentConfig.BUCKET_ENV)).isEqualTo("store_bucket")
        }
        assertThat(pod.containers.drop(1)).allSatisfy { container ->
            assertThat(configKey(container, "STORE_BUCKET")).isEqualTo("store_bucket")
            assertThat(configKey(container, "METRICS_STORE_PREFIX")).isEqualTo("metrics_store_prefix")
            assertThat(configKey(container, "LOGS_STORE_PREFIX")).isEqualTo("logs_store_prefix")
            assertThat(configKey(container, "TRACES_STORE_PREFIX")).isEqualTo("traces_store_prefix")
            assertThat(env(container))
                .containsEntry("STORE_REGION", BUCKET_REGION)
                .containsEntry("STORE_ENDPOINT", "s3.$BUCKET_REGION.amazonaws.com")
                .containsEntry("TENANT", "compactor")
                .containsEntry("CLUSTER_NAME", "compactor")
        }
    }

    @Test
    fun `the holder identity comes from the cluster label and the pod name`() {
        val lease = container(CompactorManifestBuilder.LEASE_CONTAINER)

        assertThat(
            lease.env
                .single { it.name == "CLUSTER_LABEL" }
                .valueFrom.configMapKeyRef.key,
        ).isEqualTo("cluster_name")
        assertThat(
            lease.env
                .single { it.name == "POD_NAME" }
                .valueFrom.fieldRef.fieldPath,
        ).isEqualTo("metadata.name")
    }
}
