package com.rustyrazorblade.easydblab

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.rustyrazorblade.easydblab.YamlTestSupport.nodeAt
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterLabels
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.WorkloadScrapeConfig
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.sidecar.SidecarManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.EMRClusterConfig
import com.rustyrazorblade.easydblab.providers.aws.EMRClusterResult
import com.rustyrazorblade.easydblab.services.DefaultAnnotationMirror
import com.rustyrazorblade.easydblab.services.DefaultEMRProvisioningService
import com.rustyrazorblade.easydblab.services.DefaultStressJobService
import com.rustyrazorblade.easydblab.services.EcrPullSecretService
import com.rustyrazorblade.easydblab.services.EmrClusterProvisioningConfig
import com.rustyrazorblade.easydblab.services.GrafanaAnnotationSource
import com.rustyrazorblade.easydblab.services.LokiPushClient
import com.rustyrazorblade.easydblab.services.MirroredAnnotation
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.ObservabilityResponse
import com.rustyrazorblade.easydblab.services.QueryableWait
import com.rustyrazorblade.easydblab.services.RecordingObservabilityHttp
import com.rustyrazorblade.easydblab.services.StressJobConfig
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.aws.EMRService
import io.fabric8.kubernetes.api.model.Container
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Every place that sets `cluster` on a signal also sets `experiment` and `edl_tenant`.
 *
 * Layer 1 renders each registered cluster setter and checks the labels it sets. Layer 2 scans the
 * shipped sources for the patterns that set `cluster` and fails on a file it does not know, so a
 * new pipeline that stamps only `cluster` cannot slip in: it must be registered, and registering it
 * means writing its layer-1 check here. The JFR shipper's behaviour is checked by
 * `edl-profiling-reconcile.test.sh`; here only its label set is read.
 */
class ExperimentLabelCoverageTest : BaseKoinTest() {
    private val control = ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a", "i-control")
    private val state =
        ClusterState(
            name = "lab",
            clusterId = "0f3c",
            versions = mutableMapOf(),
            s3Bucket = "acct-bucket",
            hosts = mapOf(ServerType.Control to listOf(control)),
            infrastructure = InfrastructureState(vpcId = "vpc-1", region = "us-west-2"),
            initConfig = InitConfig(region = "us-west-2", tenant = TENANT, experiment = EXPERIMENT),
        )
    private val expected = mapOf("cluster" to CLUSTER, "experiment" to EXPERIMENT, "edl_tenant" to TENANT)

    private lateinit var templateService: TemplateService

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager>().also { whenever(it.load()).thenReturn(state) } }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        templateService = getKoin().get()
    }

    // ---------------------------------------------------------------- layer 1: the collector

    private fun collectorConfig(): String =
        OtelManifestBuilder(templateService)
            .buildConfigMap(
                listOf(
                    WorkloadScrapeConfig(kitName = "static", jobName = "static", port = 9100, path = "/metrics"),
                    WorkloadScrapeConfig(kitName = "pods", jobName = "pods", port = 9187, path = "/metrics", podSelector = "app=x"),
                ),
                telemetryRedirect = null,
                cni = CniMode.Cilium,
            ).data
            .getValue("otel-collector-config.yaml")

    @Test
    fun `every collector scrape job that sets cluster sets the experiment and the tenant from the environment`() {
        val jobs = nodeAt(collectorConfig(), "receivers", "prometheus", "config", "scrape_configs") as YamlList
        val settingCluster =
            jobs.items.map { it as YamlMap }.mapNotNull { job ->
                val name = job.get<YamlScalar>("job_name")!!.content
                relabelTargets(job).takeIf { "cluster" in it }?.let { name to it }
            }

        // beyla, ebpf-exporter, yace and otel-collector in the file; the rest from OtelManifestBuilder.
        assertThat(settingCluster.map { it.first })
            .contains("beyla", "ebpf-exporter", "yace", "otel-collector", "static-static", "pods-pods", "cilium-agent", "cilium-operator")
        for ((job, targets) in settingCluster) {
            assertThat(LabelChecks.missing(targets.keys)).describedAs("scrape job $job").isEmpty()
            assertThat(targets.filterKeys { it in expected }).describedAs("scrape job $job").isEqualTo(ENV_SOURCES)
        }
    }

    @Test
    fun `the collector's resource cluster processor stamps all three, and every pipeline that sets cluster runs it`() {
        val yaml = collectorConfig()
        val upserts =
            (nodeAt(yaml, "processors", "resource/cluster", "attributes") as YamlList)
                .items
                .map { it as YamlMap }
                .associate { it.get<YamlScalar>("key")!!.content to it.get<YamlScalar>("value")!!.content }

        assertThat(upserts).isEqualTo(ENV_SOURCES)
        val pipelines = nodeAt(yaml, "service", "pipelines") as YamlMap
        val withoutIt =
            pipelines.entries.filter { (_, body) ->
                val processors =
                    (body as YamlMap)
                        .get<YamlList>("processors")
                        ?.items
                        .orEmpty()
                        .map { (it as YamlScalar).content }
                "resource/cluster" !in processors
            }
        assertThat(withoutIt.keys.map { it.content }).isEmpty()
    }

    @Test
    fun `the collector DaemonSet provides every variable its labels read`() {
        val env =
            envNames(
                OtelManifestBuilder(templateService)
                    .buildDaemonSet()
                    .spec.template.spec.containers
                    .single(),
            )

        assertThat(env).contains("CLUSTER_NAME", "EXPERIMENT", "TENANT")
    }

    // ---------------------------------------------------------------- layer 1: the stress job

    @Test
    fun `the stress sidecar config relabels all three from the environment the job gives it`() {
        val sidecarConfig = resource("configuration/cassandra/otel-stress-sidecar-config.yaml")
        val jobs = nodeAt(sidecarConfig, "receivers", "prometheus", "config", "scrape_configs") as YamlList
        val targets = relabelTargets(jobs.items.single() as YamlMap)
        assertThat(targets.filterKeys { it in expected }).isEqualTo(ENV_SOURCES)

        val job = stressJobService().buildJob(StressJobConfig("stress-1", "img", listOf("run"), "10.0.1.6"))
        val containers = job.spec.template.spec.initContainers + job.spec.template.spec.containers
        val sidecar = containers.single { it.name == "otel-sidecar" }
        val env = sidecar.env.associate { it.name to it.value }
        assertThat(env).containsEntry("CLUSTER_NAME", CLUSTER).containsEntry("EXPERIMENT", EXPERIMENT).containsEntry("TENANT", TENANT)
    }

    @Test
    fun `the stress job's Pyroscope agent labels its profiles with all three`() {
        val job = stressJobService().buildJob(StressJobConfig("stress-1", "img", listOf("run"), "10.0.1.6"))
        val stress =
            job.spec.template.spec.containers
                .single { it.name == "stress" }

        assertThat(pyroscopeLabels(agentOptions(stress, "JDK_JAVA_OPTIONS"))).containsAllEntriesOf(expected)
    }

    private fun stressJobService(): DefaultStressJobService {
        val pullSecrets = mock<EcrPullSecretService>()
        whenever(pullSecrets.ensureFor(any(), any(), any())).thenReturn("")
        return DefaultStressJobService(mock(), getKoin().get(), EventBus(), templateService, pullSecrets)
    }

    // ---------------------------------------------------------------- layer 1: profiles

    @Test
    fun `Alloy labels every eBPF target with all three, read from variables its DaemonSet sets`() {
        val builder = PyroscopeManifestBuilder(templateService)
        val alloy = builder.buildEbpfConfigMap().data.getValue("config.alloy")
        val rules =
            Regex("""rule\s*\{\s*target_label\s*=\s*"([^"]+)"\s*replacement\s*=\s*sys\.env\("([^"]+)"\)\s*}""")
                .findAll(alloy)
                .associate { it.groupValues[1] to "\${env:${it.groupValues[2]}}" }

        assertThat(rules.filterKeys { it in expected }).isEqualTo(ENV_SOURCES)
        assertThat(
            envNames(
                builder
                    .buildEbpfDaemonSet()
                    .spec.template.spec.containers
                    .single(),
            ),
        ).contains("CLUSTER_NAME", "EXPERIMENT", "TENANT")
    }

    @Test
    fun `the Cassandra sidecar's Pyroscope agent labels its profiles with all three`() {
        val daemonSet =
            SidecarManifestBuilder(templateService)
                .buildDaemonSet(image = "img", controlNodeIp = control.privateIp, labels = ClusterLabels.of(state))
        val sidecar =
            daemonSet.spec.template.spec.containers
                .single()

        assertThat(pyroscopeLabels(agentOptions(sidecar, "JAVA_TOOL_OPTIONS"))).containsAllEntriesOf(expected)
    }

    @Test
    fun `EMR's Pyroscope agent labels its profiles with all three`() {
        val emr = mock<EMRService>()
        val captured = argumentCaptor<EMRClusterConfig>()
        whenever(emr.createCluster(captured.capture())).thenReturn(EMRClusterResult("j-1", "lab-spark", null, "STARTING"))
        whenever(emr.waitForClusterReady("j-1")).thenReturn(EMRClusterResult("j-1", "lab-spark", "dns", "WAITING"))

        DefaultEMRProvisioningService(emr, mock<ObjectStore>(), templateService, EventBus()).provisionEmrCluster(
            EmrClusterProvisioningConfig(
                clusterName = "lab",
                masterInstanceType = "m5.xlarge",
                workerInstanceType = "m5.xlarge",
                workerCount = 1,
                subnetId = "subnet-1",
                securityGroupId = "sg-1",
                keyName = "key",
                clusterState = state,
                tags = emptyMap(),
            ),
        )

        val export =
            captured.firstValue.configurations
                .single { it.classification == "spark-env" }
                .configurations
                .single()
        assertThat(labelPairs(export.properties.getValue("PYROSCOPE_LABELS"))).containsAllEntriesOf(expected)
    }

    @Test
    fun `the presto and trino start scripts label profiles with all three, reading the experiment from cluster-config`() {
        for (kit in listOf("presto", "trino")) {
            val script = resource("kits/$kit/bin/start.sh.template")
            val labelSets = Regex("""-Dpyroscope\.labels=([^"\s]+)""").findAll(script).map { labelPairs(it.groupValues[1]) }.toList()

            assertThat(labelSets).describedAs(kit).isNotEmpty()
            for (labels in labelSets) {
                assertThat(labels).describedAs(kit).containsKey("cluster")
                assertThat(LabelChecks.missing(labels.keys)).describedAs(kit).isEmpty()
                assertThat(labels["edl_tenant"]).describedAs(kit).isEqualTo("\${TENANT}")
            }
            assertThat(script).describedAs(kit).contains("{.data.experiment}")
        }
    }

    @Test
    fun `the JFR shipper names every profile with all three`() {
        val script = File("packer/cassandra/bin/edl-profiling-reconcile").readText()
        // The series name, `cassandra{k=${V},...}`: its braces hold `${...}` references.
        val seriesLabels = labelPairs(Regex("""cassandra\{((?:\$\{[^}]*}|[^{}])*)}""").find(script)!!.groupValues[1])

        assertThat(seriesLabels).containsKey("cluster")
        assertThat(LabelChecks.missing(seriesLabels.keys)).isEmpty()
        // Its counters' OTLP resource.
        val counterAttributes = Regex(""""key":\s*"([^"]+)",\s*"value"""").findAll(script).map { it.groupValues[1] }.toSet()
        assertThat(counterAttributes).contains("cluster")
        assertThat(LabelChecks.missing(counterAttributes)).isEmpty()
    }

    // ---------------------------------------------------------------- layer 1: backends and the mirror

    @Test
    fun `Tempo's external labels carry all three, from variables its Deployment sets`() {
        val builder = TempoManifestBuilder(templateService)
        val tempo =
            builder
                .buildConfigMap()
                .data.values
                .single()
        val external = (nodeAt(tempo, "metrics_generator", "registry", "external_labels") as YamlMap)
        val labels = external.entries.entries.associate { it.key.content to (it.value as YamlScalar).content }

        assertThat(labels.filterKeys { it in expected })
            .isEqualTo(mapOf("cluster" to "\${CLUSTER_NAME}", "experiment" to "\${EXPERIMENT}", "edl_tenant" to "\${TENANT}"))
        assertThat(
            envNames(
                builder
                    .buildDeployment()
                    .spec.template.spec.containers
                    .single(),
            ),
        ).contains("CLUSTER_NAME", "EXPERIMENT", "TENANT")
    }

    @Test
    fun `Beyla is given the experiment and the tenant beside the cluster`() {
        val beyla =
            BeylaManifestBuilder(templateService)
                .buildDaemonSet()
                .spec.template.spec.containers
                .single()

        assertThat(envNames(beyla)).contains("CLUSTER_NAME", "EXPERIMENT", "TENANT")
    }

    @Test
    fun `Loki indexes the experiment and the tenant beside the cluster`() {
        val loki = LokiManifestBuilder(templateService).buildConfigMap().data.getValue(LokiManifestBuilder.CONFIG_FILE)
        val rules = nodeAt(loki, "limits_config", "otlp_config", "resource_attributes", "attributes_config") as YamlList
        val indexed =
            rules.items
                .map { it as YamlMap }
                .filter { it.get<YamlScalar>("action")?.content == "index_label" }
                .flatMap { rule -> rule.get<YamlList>("attributes")!!.items.map { (it as YamlScalar).content } }

        assertThat(indexed).contains("cluster", "experiment", "edl_tenant")
    }

    @Test
    fun `every mirrored annotation's stream carries all three`() {
        val http = RecordingObservabilityHttp(ObservabilityResponse(204, ""))
        val now = Instant.parse("2026-10-14T12:00:00Z")
        val mirror =
            DefaultAnnotationMirror(
                LokiPushClient(http),
                object : GrafanaAnnotationSource {
                    override fun fetchAnnotations(controlHost: ClusterHost) = "[]"
                },
                getKoin().get(),
                EventBus(),
                Clock.fixed(now, ZoneOffset.UTC),
                QueryableWait(looks = 1, interval = Duration.ZERO),
            )

        mirror.push(MirroredAnnotation(id = 7, text = "x", tags = emptyList(), time = now.minusSeconds(60).toEpochMilli())).getOrThrow()

        val stream =
            Json
                .parseToJsonElement(http.calls.single().body)
                .jsonObject
                .getValue("streams")
                .jsonArray
                .single()
                .jsonObject
                .getValue("stream")
                .jsonObject
                .mapValues { it.value.jsonPrimitive.content }
        assertThat(stream).containsAllEntriesOf(expected)
    }

    @Test
    fun `ClusterLabels gives all three from the cluster state`() {
        assertThat(ClusterLabels.of(state).asMap()).isEqualTo(expected)
        assertThat(labelPairs(ClusterLabels.of(state).pyroscopePairs())).isEqualTo(expected)
    }

    // ---------------------------------------------------------------- layer 2: the source scan

    @Test
    fun `every file that sets cluster is registered`() {
        val unregistered = ClusterSetterScan.unregistered(File("."), ClusterSetterScan.SCANNED_DIRS, REGISTERED + NOT_SETTERS.keys)

        assertThat(unregistered).isEmpty()
    }

    @Test
    fun `every registered file still exists`() {
        assertThat((REGISTERED + NOT_SETTERS.keys).filterNot { File(it).isFile }).isEmpty()
    }

    @Test
    fun `a new file that sets only cluster fails as an unregistered cluster setter, naming it`(
        @TempDir root: File,
    ) {
        File(root, "src/main/resources/new-pipeline.yaml").apply {
            parentFile.mkdirs()
            writeText("relabel_configs:\n  - target_label: cluster\n    replacement: x\n")
        }
        File(root, "src/main/kotlin/Labels.kt").apply {
            parentFile.mkdirs()
            writeText("val labels = mapOf(\"cluster\" to name)\n")
        }
        File(root, "packer/bin/ship").apply {
            parentFile.mkdirs()
            writeText("name=\"app{host=\${HOST},cluster=\${CLUSTER_NAME}}\"\n")
        }
        File(root, "packer/bin/push").writeText("{\"key\": \"cluster\", \"value\": {\"stringValue\": strenv(C)}}\n")
        File(root, "src/main/resources/README.md").writeText("target_label: cluster\n")

        assertThat(ClusterSetterScan.unregistered(root, ClusterSetterScan.SCANNED_DIRS, emptySet()))
            .containsExactlyInAnyOrder(
                "unregistered cluster setter: src/main/resources/new-pipeline.yaml",
                "unregistered cluster setter: src/main/kotlin/Labels.kt",
                "unregistered cluster setter: packer/bin/ship",
                "unregistered cluster setter: packer/bin/push",
            )
    }

    @Test
    fun `a label set with only cluster misses the experiment and the tenant`() {
        assertThat(LabelChecks.missing(setOf("cluster", "instance"))).containsExactlyInAnyOrder("experiment", "edl_tenant")
        assertThat(LabelChecks.missing(setOf("instance"))).isEmpty()
    }

    // ---------------------------------------------------------------- helpers

    /** The labels a scrape job's relabel rules set, with the value each sets. */
    private fun relabelTargets(job: YamlMap): Map<String, String> =
        listOf("relabel_configs", "metric_relabel_configs")
            .flatMap { job.get<YamlList>(it)?.items.orEmpty() }
            .map { it as YamlMap }
            .mapNotNull { rule ->
                rule.get<YamlScalar>("target_label")?.content?.let { it to rule.get<YamlScalar>("replacement")?.content.orEmpty() }
            }.toMap()

    private fun envNames(container: Container): Set<String> = container.env.map { it.name }.toSet()

    private fun agentOptions(
        container: Container,
        variable: String,
    ): String = container.env.single { it.name == variable }.value

    private fun pyroscopeLabels(options: String): Map<String, String> =
        labelPairs(Regex("""-Dpyroscope\.labels=(\S+)""").find(options)!!.groupValues[1])

    /** `k=v,k=v` as a map; the form every Pyroscope label string here takes. */
    private fun labelPairs(pairs: String): Map<String, String> =
        pairs.split(",").associate { it.substringBefore("=") to it.substringAfter("=") }

    private fun resource(path: String): String = File("src/main/resources/com/rustyrazorblade/easydblab/$path").readText()

    private companion object {
        const val CLUSTER = "lab-0f3c"
        const val EXPERIMENT = "e1"
        const val TENANT = "acme"

        /** Where a setter that reads the environment takes each label from. */
        val ENV_SOURCES =
            mapOf("cluster" to "\${env:CLUSTER_NAME}", "experiment" to "\${env:EXPERIMENT}", "edl_tenant" to "\${env:TENANT}")

        private const val MAIN = "src/main/kotlin/com/rustyrazorblade/easydblab"
        private const val RES = "src/main/resources/com/rustyrazorblade/easydblab"

        /** Every file that sets `cluster`, each checked by a layer-1 test above. */
        val REGISTERED =
            setOf(
                "$RES/configuration/otel/otel-collector-config.yaml",
                "$MAIN/configuration/otel/OtelManifestBuilder.kt",
                "$RES/configuration/cassandra/otel-stress-sidecar-config.yaml",
                "$MAIN/services/StressJobService.kt",
                "$RES/configuration/pyroscope/config.alloy",
                "$MAIN/configuration/pyroscope/PyroscopeManifestBuilder.kt",
                "$MAIN/configuration/sidecar/SidecarManifestBuilder.kt",
                "$MAIN/services/EMRProvisioningService.kt",
                "$RES/kits/presto/bin/start.sh.template",
                "$RES/kits/trino/bin/start.sh.template",
                "packer/cassandra/bin/edl-profiling-reconcile",
                "$RES/configuration/tempo/tempo.yaml",
                "$MAIN/configuration/tempo/TempoManifestBuilder.kt",
                "$MAIN/configuration/beyla/BeylaManifestBuilder.kt",
                "$RES/configuration/loki/loki.yaml",
                "$MAIN/services/AnnotationMirror.kt",
                "$MAIN/configuration/ClusterLabels.kt",
            )

        /** Files that match a setter pattern but only read `cluster`, with the reason. */
        val NOT_SETTERS =
            mapOf(
                "$MAIN/services/LogQl.kt" to "builds LogQL selectors that read cluster",
                "$MAIN/mcp/StatusCache.kt" to "a field of the status JSON, not a signal label",
            )
    }
}

/** The label rule this test enforces: a label set with `cluster` also has the other two. */
internal object LabelChecks {
    private val COMPANIONS = setOf(Constants.Observability.EXPERIMENT_LABEL, Constants.Observability.TENANT_LABEL)

    /** The labels [labels] lacks: empty when it sets no `cluster` or sets all three. */
    fun missing(labels: Set<String>): Set<String> = if ("cluster" in labels) COMPANIONS - labels else emptySet()
}

/** Finds files that set the `cluster` label, by the patterns every known setter uses. */
internal object ClusterSetterScan {
    /** The shipped sources: Kotlin, packaged resources, and the AMI's scripts. */
    val SCANNED_DIRS = listOf("src/main/kotlin", "src/main/resources", "packer")

    private val PATTERNS =
        listOf(
            // YAML relabels: `- target_label: cluster`.
            Regex("""target_label:\s*['"]?cluster['"]?\s*$"""),
            // Kotlin relabels: PrometheusRelabelConfig(targetLabel = "cluster", ...).
            Regex("""targetLabel\s*=\s*"cluster""""),
            // Alloy rules: target_label = "cluster".
            Regex("""target_label\s*=\s*"cluster""""),
            // The OTel resource processor: `key: cluster`.
            Regex("""key:\s*['"]?cluster['"]?\s*$"""),
            // Kotlin label maps: "cluster" to ...
            Regex(""""cluster"\s+to\b"""),
            // Pyroscope label strings: `...,cluster=$x`, `labels=cluster=${x}`, `{cluster=${x}`.
            Regex("""[,{"'=]cluster=\$"""),
            // YAML label maps filled from the environment: `cluster: ${CLUSTER_NAME}`.
            Regex("""^\s*cluster:\s*\$\{"""),
            // OTLP resource attributes built as JSON: {"key": "cluster", ...}.
            Regex(""""key":\s*"cluster""""),
        )

    /** Documentation, dashboards (readers) and binaries are not scanned; nor are the shell tests. */
    private val SKIPPED_EXTENSIONS = setOf("json", "md", "png", "jpg", "jpeg", "gif", "svg", "ico", "jar", "gz", "zip")

    /** Each file under [dirs] of [root] that sets `cluster`, as a path relative to [root]. */
    fun hits(
        root: File,
        dirs: List<String>,
    ): Set<String> =
        dirs
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && scanned(it) } }
            .filter { file -> file.useLines { lines -> lines.any { line -> PATTERNS.any { it.containsMatchIn(line) } } } }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()

    /** One "unregistered cluster setter" failure per hit not in [known]. */
    fun unregistered(
        root: File,
        dirs: List<String>,
        known: Set<String>,
    ): List<String> = hits(root, dirs).filterNot { it in known }.sorted().map { "unregistered cluster setter: $it" }

    private fun scanned(file: File): Boolean = file.extension.lowercase() !in SKIPPED_EXTENSIONS && !file.name.endsWith(".test.sh")
}
