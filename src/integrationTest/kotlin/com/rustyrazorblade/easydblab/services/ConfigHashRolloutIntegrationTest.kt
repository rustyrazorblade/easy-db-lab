package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * Applies the Tempo and Pyroscope workloads to a real K3s cluster, the way the observability deploy
 * does — built by their manifest builders, hashed by [ConfigHashAnnotator] against the runtime
 * `cluster-config`, and server-side applied — and watches what the Deployment controller does.
 *
 * An unchanged configuration (a deploy where only dashboards changed) must leave both Deployments
 * at the same generation with no new ReplicaSet, so their pods keep running. A change to Tempo's
 * configuration must roll Tempo, and only Tempo. Whether the pods themselves become Ready is beside
 * the point here; the generation and the ReplicaSets are the controller's own record of a rollout.
 *
 * The builders place the workloads in `default`; this test moves them into a namespace of its own
 * on the [SharedK3s] cluster.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigHashRolloutIntegrationTest {
    companion object {
        private const val NAMESPACE = "config-hash-rollout"
        private const val TEMPLATE_NAMESPACE = "config-hash-template-only"
    }

    private val controlHost =
        ClusterHost(publicIp = "1.2.3.4", privateIp = "10.0.0.1", alias = "control0", availabilityZone = "us-west-2a")
    private val clusterConfig =
        mapOf(
            "control_node_ip" to "10.0.0.1",
            "aws_region" to "us-west-2",
            "s3_bucket" to "acct-bucket",
            "traces_s3_prefix" to "observability/traces",
            "cluster_name" to "test",
        )

    private lateinit var client: KubernetesClient
    private lateinit var manifests: DefaultK8sManifestOperations
    private lateinit var namespaces: DefaultK8sNamespaceOperations
    private lateinit var tempo: TempoManifestBuilder
    private lateinit var pyroscope: PyroscopeManifestBuilder
    private lateinit var otel: OtelManifestBuilder
    private lateinit var otelSync: OtelSyncService
    private lateinit var stateManager: ClusterStateManager
    private lateinit var user: User

    @BeforeAll
    fun setup() {
        SharedK3s.createNamespace(NAMESPACE)
        SharedK3s.createNamespace(TEMPLATE_NAMESPACE)
        client = SharedK3s.client()
        val clientProvider = mock<K8sClientProvider>()
        whenever(clientProvider.createClient(any())).thenAnswer { SharedK3s.client() }
        manifests = DefaultK8sManifestOperations(clientProvider, EventBus())
        namespaces = DefaultK8sNamespaceOperations(clientProvider, EventBus())

        stateManager = mock()
        whenever(stateManager.load()).thenReturn(
            ClusterState(name = "test", versions = mutableMapOf(), s3Bucket = "acct-bucket"),
        )
        user =
            User(
                region = "us-west-2",
                email = "test@example.com",
                keyName = "",
                awsProfile = "",
                awsAccessKey = "",
                awsSecret = "",
            )
        val templateService = TemplateService(stateManager, user)
        tempo = TempoManifestBuilder(templateService)
        pyroscope = PyroscopeManifestBuilder(templateService)
        otel = OtelManifestBuilder(templateService)
        val k8sService = DefaultK8sService(clientProvider, EventBus())
        otelSync =
            DefaultOtelSyncService(clientProvider, k8sService, otel, stateManager, user, ConfigChangeReport(k8sService, EventBus()))
    }

    @AfterAll
    fun tearDown() {
        client.close()
    }

    /** Hashes and applies [resources] exactly as the deploy does, returning the applied resources. */
    private fun deploy(resources: List<HasMetadata>): List<HasMetadata> {
        val known =
            mapOf("cluster-config" to clusterConfig) +
                resources.filterIsInstance<ConfigMap>().associate { it.metadata.name to it.data.orEmpty() }
        return ConfigHashAnnotator.annotate(resources, known).onEach { manifests.applyResource(controlHost, it).getOrThrow() }
    }

    private fun stack(tempoConfigEdit: (String) -> String = { it }): List<HasMetadata> {
        val tempoResources =
            tempo.buildAllResources().map { resource ->
                if (resource is ConfigMap) {
                    ConfigMapBuilder(resource)
                        .addToData("tempo.yaml", tempoConfigEdit(resource.data.getValue("tempo.yaml")))
                        .build()
                } else {
                    resource
                }
            }
        return (tempoResources + pyroscope.buildServerResources()).onEach { it.metadata.namespace = NAMESPACE }
    }

    private fun generation(
        name: String,
        namespace: String = NAMESPACE,
    ): Long =
        client
            .apps()
            .deployments()
            .inNamespace(namespace)
            .withName(name)
            .get()
            .metadata.generation

    private fun replicaSets(
        app: String,
        namespace: String = NAMESPACE,
    ): Set<String> =
        client
            .apps()
            .replicaSets()
            .inNamespace(namespace)
            .withLabel("app.kubernetes.io/name", app)
            .list()
            .items
            .map { it.metadata.name }
            .toSet()

    /** Waits for the Deployment controller to have created a ReplicaSet for [app]'s current template. */
    private fun awaitReplicaSets(
        app: String,
        atLeast: Int,
        namespace: String = NAMESPACE,
    ): Set<String> {
        val deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos()
        var sets = replicaSets(app, namespace)
        while (sets.size < atLeast && System.nanoTime() < deadline) {
            Thread.sleep(500)
            sets = replicaSets(app, namespace)
        }
        return sets
    }

    @Test
    fun `an unchanged configuration rolls nothing, and a Tempo config change rolls Tempo only`() {
        deploy(stack())
        val tempoSets = awaitReplicaSets("tempo", 1)
        val pyroscopeSets = awaitReplicaSets("pyroscope", 1)
        val tempoGeneration = generation("tempo")
        val pyroscopeGeneration = generation("pyroscope")

        // Only dashboards changed: the observability resources are rebuilt identically.
        deploy(stack())
        Thread.sleep(2_000)

        assertThat(generation("tempo")).isEqualTo(tempoGeneration)
        assertThat(generation("pyroscope")).isEqualTo(pyroscopeGeneration)
        assertThat(replicaSets("tempo")).isEqualTo(tempoSets)
        assertThat(replicaSets("pyroscope")).isEqualTo(pyroscopeSets)

        // Tempo's configuration changes.
        deploy(stack { it.replace("max_block_duration: 5m", "max_block_duration: 6m") })

        assertThat(generation("tempo")).isGreaterThan(tempoGeneration)
        assertThat(awaitReplicaSets("tempo", tempoSets.size + 1)).hasSize(tempoSets.size + 1)
        assertThat(generation("pyroscope")).isEqualTo(pyroscopeGeneration)
        assertThat(replicaSets("pyroscope")).isEqualTo(pyroscopeSets)
    }

    /** Records every [Event.Grafana.WorkloadConfigCompared] emitted on a new [EventBus]. */
    private fun recordingEventBus(emitted: MutableList<Event.Grafana.WorkloadConfigCompared>): EventBus =
        EventBus().also {
            it.addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        (envelope.event as? Event.Grafana.WorkloadConfigCompared)?.let { event -> emitted += event }
                    }

                    override fun close() = Unit
                },
            )
        }

    /**
     * Hashes, reports and applies [resources] in [TEMPLATE_NAMESPACE] as the deploy does, returning
     * what the report emitted.
     */
    private fun deployReported(resources: List<HasMetadata>): List<Event.Grafana.WorkloadConfigCompared> {
        val known =
            mapOf("cluster-config" to clusterConfig) +
                resources.filterIsInstance<ConfigMap>().associate { it.metadata.name to it.data.orEmpty() }
        val hashed = ConfigHashAnnotator.annotate(resources, known)
        val emitted = mutableListOf<Event.Grafana.WorkloadConfigCompared>()
        ConfigChangeReport(namespaces, recordingEventBus(emitted)).report(controlHost, hashed, TEMPLATE_NAMESPACE)
        hashed.forEach { manifests.applyResource(controlHost, it).getOrThrow() }
        return emitted
    }

    /**
     * The Pyroscope server in [TEMPLATE_NAMESPACE], optionally without its startup probe. The
     * namespace is its own so no other test's Pyroscope render is already running there.
     */
    private fun pyroscopeServer(withStartupProbe: Boolean = true): List<HasMetadata> =
        pyroscope
            .buildServerResources()
            .map { resource ->
                if (resource is Deployment && !withStartupProbe) {
                    DeploymentBuilder(resource)
                        .editSpec()
                        .editTemplate()
                        .editSpec()
                        .editFirstContainer()
                        .withStartupProbe(null)
                        .endContainer()
                        .endSpec()
                        .endTemplate()
                        .endSpec()
                        .build()
                } else {
                    resource
                }
            }.onEach { it.metadata.namespace = TEMPLATE_NAMESPACE }

    /**
     * Kubernetes rolls a workload on any pod-template change, not only a ConfigMap change. A change
     * to the template alone (here, Pyroscope gaining a startup probe with its ConfigMap untouched) must
     * be reported as changed and must roll; the same render applied again must be reported unchanged
     * and must not roll. The report once said "unchanged, left running" while Pyroscope rolled.
     */
    @Test
    fun `a pod-template-only change is reported changed and rolls, and an unchanged re-apply does neither`() {
        val pyroscopeWorkload = WorkloadRef(WorkloadKind.Deployment, "pyroscope").toString()
        deployReported(pyroscopeServer(withStartupProbe = false))
        val oldSets = awaitReplicaSets("pyroscope", 1, TEMPLATE_NAMESPACE)
        val oldGeneration = generation("pyroscope", TEMPLATE_NAMESPACE)

        val templateChange = deployReported(pyroscopeServer())

        assertThat(templateChange).containsExactly(Event.Grafana.WorkloadConfigCompared(pyroscopeWorkload, changed = true))
        assertThat(generation("pyroscope", TEMPLATE_NAMESPACE)).isGreaterThan(oldGeneration)
        val rolledSets = awaitReplicaSets("pyroscope", oldSets.size + 1, TEMPLATE_NAMESPACE)
        assertThat(rolledSets).hasSize(oldSets.size + 1)
        val rolledGeneration = generation("pyroscope", TEMPLATE_NAMESPACE)

        val reapply = deployReported(pyroscopeServer())
        Thread.sleep(2_000)

        assertThat(reapply).containsExactly(Event.Grafana.WorkloadConfigCompared(pyroscopeWorkload, changed = false))
        assertThat(generation("pyroscope", TEMPLATE_NAMESPACE)).isEqualTo(rolledGeneration)
        assertThat(replicaSets("pyroscope", TEMPLATE_NAMESPACE)).isEqualTo(rolledSets)
    }

    /** The deploy compares these against the hashes it is about to apply to report what rolls. */
    @Test
    fun `the running config hash is read back from the cluster, and null for a workload that is not there`() {
        val applied = deploy(stack())
        val tempoHash =
            applied
                .filterIsInstance<Deployment>()
                .single { it.metadata.name == "tempo" }
                .spec.template.metadata.annotations[Constants.K8s.CONFIG_HASH_ANNOTATION]
        val tempo = WorkloadRef(WorkloadKind.Deployment, "tempo")
        val absent = WorkloadRef(WorkloadKind.DaemonSet, "not-deployed")

        val running = namespaces.workloadConfigHashes(controlHost, listOf(tempo, absent), NAMESPACE).getOrThrow()

        assertThat(tempoHash).isNotBlank()
        assertThat(running).containsEntry(tempo, tempoHash).containsEntry(absent, null)
    }

    /**
     * The collector's stage of a stack deploy (`up`, `grafana update-config`), as
     * [DefaultObservabilityStackService] runs it: scrape jobs read from the metrics registry, built
     * and hashed by [CollectorResources], reported, then applied. Returns what the report emitted.
     */
    private fun deployCollectorStage(): List<Event.Grafana.WorkloadConfigCompared> {
        val scrapeConfigs = otel.listWorkloadScrapeConfigs(client)
        val resources = CollectorResources.build(otel, controlHost, stateManager.load(), user.region, scrapeConfigs)
        val emitted = mutableListOf<Event.Grafana.WorkloadConfigCompared>()
        ConfigChangeReport(namespaces, recordingEventBus(emitted)).report(controlHost, resources, Constants.K8s.NAMESPACE)
        resources.forEach { manifests.applyResource(controlHost, it).getOrThrow() }
        return emitted
    }

    private fun collectorGeneration(): Long =
        client
            .apps()
            .daemonSets()
            .inNamespace(Constants.K8s.NAMESPACE)
            .withName(Constants.OtelCollector.SERVICE_NAME)
            .get()
            .metadata.generation

    /**
     * A kit start re-syncs the collector. The collector must roll because its config hash changed,
     * and the hash must be the one a stack deploy computes, so the next `up` or
     * `grafana update-config` sees the collector unchanged and leaves it running.
     *
     * The collector's builder places it in `default`; no other test on the [SharedK3s] cluster
     * deploys it.
     */
    @Test
    fun `after a kit-driven collector sync, the next stack deploy leaves the collector running`() {
        val kitMetrics =
            ConfigMapBuilder()
                .withNewMetadata()
                .withName("easydblab-metrics-hash-rollout-kit")
                .withNamespace(NAMESPACE)
                .addToLabels("easydblab.com/workload-metrics", "true")
                .endMetadata()
                .addToData(
                    mapOf("kit-name" to "hash-rollout-kit", "job-name" to "hash-rollout-kit", "port" to "9180", "path" to "/metrics"),
                ).build()
        try {
            deployCollectorStage()
            val deployedGeneration = collectorGeneration()

            client.resource(kitMetrics).create()
            otelSync.syncConfigMap(controlHost).getOrThrow()
            val syncedGeneration = collectorGeneration()
            assertThat(syncedGeneration).isGreaterThan(deployedGeneration)

            val compared = deployCollectorStage()
            Thread.sleep(2_000)

            assertThat(compared).containsExactly(
                Event.Grafana.WorkloadConfigCompared(
                    WorkloadRef(WorkloadKind.DaemonSet, Constants.OtelCollector.SERVICE_NAME).toString(),
                    changed = false,
                ),
            )
            assertThat(collectorGeneration()).isEqualTo(syncedGeneration)
        } finally {
            client.resource(kitMetrics).delete()
            client.resourceList(otel.buildAllResources()).delete()
        }
    }
}
