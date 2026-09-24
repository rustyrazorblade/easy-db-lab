package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class OtelManifestBuilderTest : BaseKoinTest() {
    private lateinit var builder: OtelManifestBuilder
    private lateinit var mockClusterStateManager: ClusterStateManager

    /** The body of one named pipeline: every line indented under it, comments excluded. */
    private fun pipeline(name: String): String {
        val lines = yamlFrom(builder.buildConfigMap(emptyList())).lines()
        val start = lines.indexOfFirst { it.trim() == name }
        check(start >= 0) { "pipeline $name is not in the rendered config" }

        return lines
            .drop(start + 1)
            .takeWhile { it.startsWith("      ") }
            .joinToString("\n")
    }

    private fun yamlFrom(configMap: ConfigMap): String =
        checkNotNull(configMap.data["otel-collector-config.yaml"]) {
            "ConfigMap missing expected data key 'otel-collector-config.yaml'"
        }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        mockClusterStateManager = getKoin().get()
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(name = "test", versions = mutableMapOf()),
        )
        val templateService = getKoin().get<TemplateService>()
        builder = OtelManifestBuilder(templateService)
    }

    /**
     * Cassandra metrics now arrive over OTLP from the OTel Java agent in the Cassandra JVM, not
     * from a Prometheus endpoint on 9000. Leaving the scrape job behind would have the collector
     * poll a port nothing listens on, once per node, for the life of every cluster.
     */
    @Test
    fun `buildConfigMap no longer scrapes the MAAC Prometheus endpoint`() {
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))

        assertThat(yaml).doesNotContain("cassandra-maac")
        assertThat(yaml).doesNotContain("localhost:9000")
    }

    /**
     * The OTel Java agent stamps its own identity and the JVM's onto every metric it exports.
     * `process.command_line` is the harmful one: it carries the full argv, so it changes on
     * `cassandra use` and mints a whole new series set, breaking panel continuity across a version
     * switch. The agent-side property that would suppress it does not work at v2.31.1, so the drop
     * has to happen in the pipeline that receives those metrics.
     */
    @Test
    fun `buildConfigMap drops the SDK resource from the OTLP metrics pipeline`() {
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))

        assertThat(yaml).contains("resource/drop_sdk_metadata:")
        assertThat(yaml).contains("key: process.command_line")
        assertThat(yaml).contains("key: telemetry.sdk.version")

        val otlpPipeline = yaml.substringAfter("metrics/otlp:").substringBefore("exporters:")
        assertThat(otlpPipeline).contains("resource/drop_sdk_metadata")
    }

    /**
     * Where the agent can read the JVM's argv as a list (a JVM in a container, such as Neo4j's),
     * it reports `process.command_args` instead of `process.command_line`. Same argv, same
     * harm: a label kilobytes long on every series. Dropping only the one key let it through.
     */
    @Test
    fun `the SDK resource drop covers the argv in both of its forms`() {
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))
        val dropBlock = yaml.substringAfter("resource/drop_sdk_metadata:\n").substringBefore("\n  resourcedetection:")

        assertThat(dropBlock).contains("key: process.command_line", "key: process.command_args")
    }

    /**
     * A JVM in a container (Neo4j's) reports `container.id`, which is new on every pod restart, so
     * each restart minted a whole new set of series. No dashboard selects on it.
     */
    @Test
    fun `the SDK resource drop removes the per-restart container id`() {
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))
        val dropBlock = yaml.substringAfter("resource/drop_sdk_metadata:\n").substringBefore("\n  resourcedetection:")

        assertThat(dropBlock).contains("key: container.id")
    }

    /**
     * spanmetrics keeps the span's whole resource on the metrics it derives, so without the drop
     * `traces_spanmetrics_*` carried the argv as a label even though the metrics and logs
     * pipelines strip it.
     */
    @Test
    fun `span-derived metrics drop the SDK resource too`() {
        assertThat(pipeline("metrics/spanmetrics:")).contains("resource/drop_sdk_metadata")
    }

    /**
     * The collector is a container. Without the node's root filesystem mounted and `root_path`
     * pointing at it, the hostmetrics scrapers describe the container, and the filesystem scraper
     * finds nothing worth reporting at all — which is why every filesystem panel was empty while
     * `filesystem:` sat in the scrapers list looking correct.
     */
    @Test
    fun `hostmetrics reads the node through a read-only host root mount`() {
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))

        // root_path belongs to the receiver, not to a scraper: indented two levels, beside
        // collection_interval, not four levels under `scrapers:`.
        assertThat(yaml).contains("\n    root_path: ${OtelManifestBuilder.HOST_ROOT_MOUNT_PATH}\n")

        val container =
            builder
                .buildDaemonSet()
                .spec.template.spec.containers
                .first()
        val mount = container.volumeMounts.first { it.name == OtelManifestBuilder.HOST_ROOT_VOLUME }

        assertThat(mount.mountPath).isEqualTo(OtelManifestBuilder.HOST_ROOT_MOUNT_PATH)
        assertThat(mount.readOnly).isTrue()

        val volume =
            builder
                .buildDaemonSet()
                .spec.template.spec.volumes
                .first { it.name == OtelManifestBuilder.HOST_ROOT_VOLUME }

        assertThat(volume.hostPath.path).isEqualTo("/")
        assertThat(volume.hostPath.type).isEqualTo("Directory")
    }

    @Test
    fun `spans get the cluster label from the pipeline, not from each producer`() {
        // Every metrics pipeline already stamps cluster this way. Doing it here too means any span
        // producer is covered — the stress job today, Beyla or another client later — instead of
        // each one carrying its own copy of the label and the next one silently missing it.
        val tracesPipeline = pipeline("traces:")

        assertThat(tracesPipeline).contains("resource/cluster")
        // Before batch, as on the metrics pipelines.
        assertThat(tracesPipeline.substringAfter("processors:")).containsSubsequence("resource/cluster", "batch")
    }

    @Test
    fun `hostmetrics scrapes paging alongside the scrapers it already had`() {
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))
        val scrapers = yaml.substringAfter("scrapers:").substringBefore("prometheus:")

        // paging is swap and page faults, and was simply never in the list.
        assertThat(scrapers).contains("paging:")
        // Regression guard: adding one scraper must not drop another.
        assertThat(scrapers).contains("cpu:", "disk:", "load:", "filesystem:", "memory:", "network:", "processes:")
    }

    @Test
    fun `the two utilization metrics are switched on explicitly`() {
        // Measured against the collector image, not assumed: system.filesystem.utilization and
        // system.paging.utilization are optional metrics and are NOT emitted by default. They are
        // the ready-made 0-1 fractions the usage-percentage panels want.
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))

        assertThat(yaml).contains("system.filesystem.utilization:")
        assertThat(yaml).contains("system.paging.utilization:")
    }

    @Test
    fun `the filesystem scraper drops squashfs, which the virtual-fs default does not`() {
        // include_virtual_filesystems defaults to false, so overlay, tmpfs, devtmpfs, sysfs and
        // proc are already gone. squashfs is device-backed, so it survives that default — and every
        // snap on an Ubuntu host is one more read-only squashfs mount sitting at 100% full.
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))

        assertThat(yaml).contains("exclude_fs_types:")
        assertThat(yaml).contains("- squashfs")
        assertThat(yaml).contains("match_type: strict")
    }

    /**
     * The logs pipeline carried the same SDK resource the metrics pipeline used to, and worse:
     * `process.command_line` is kilobytes of argv on every single record, and VictoriaLogs makes it
     * part of the stream identity, so it both bloats storage and re-keys the stream whenever
     * `cassandra use` changes the command line.
     */
    @Test
    fun `buildConfigMap drops the SDK resource from the OTLP logs pipeline too`() {
        val logsPipeline = pipeline("logs/otlp:")

        assertThat(logsPipeline).contains("resource/drop_sdk_metadata")
    }

    @Test
    fun `log-derived metrics are grouped only by logger and severity`() {
        // Both are bounded sets. Nothing here reads a log body: a message can carry a keyspace, a
        // table, a host or an id, and grouping on one would be unbounded. That restraint is the
        // whole cardinality argument for this feature, so it is asserted rather than trusted.
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))
        val countBlock = yaml.substringAfter("  count:").substringBefore("  signaltometrics:")

        assertThat(countBlock).contains("cassandra.log.records")
        assertThat(countBlock).contains("- key: logger")
        assertThat(countBlock).contains("- key: severity")
        // The only attribute keys the connector groups by.
        assertThat(Regex("- key: (\\S+)").findAll(countBlock).map { it.groupValues[1] }.toList())
            .containsOnly("logger", "severity")
    }

    @Test
    fun `GC pause duration is parsed out of the log body and summed`() {
        // GCInspector logs the per-event pause the JVM metrics only aggregate. The pattern was
        // matched against real lines from both collectors on the cluster - "G1 Young Generation GC
        // in 635ms" and "ZGC Major Cycles GC in 1465ms" share a shape, so one regex serves both.
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))

        assertThat(yaml).contains("GC in (?P<gc_event_ms>[0-9,]+)ms")
        assertThat(yaml).contains("cassandra.log.gc_event_duration_seconds")
        assertThat(yaml).contains("value: Double(attributes[\"gc_event_ms\"]) / 1000")
        // The collector name is a bounded set, so it is safe as a label.
        assertThat(yaml).contains("- key: gc_name")
    }

    @Test
    fun `compaction figures are parsed as values, never as labels`() {
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))

        assertThat(yaml).contains("cassandra.log.compaction_duration_seconds")
        assertThat(yaml).contains("cassandra.log.compaction_sstables_merged")
        assertThat(yaml).contains("cassandra.log.compaction_ratio")
        assertThat(yaml).contains("value: Double(attributes[\"compaction_ms\"]) / 1000")

        // The compaction line also carries a uuid and an sstable path. Neither is bounded, so
        // neither may become a grouping key on any of the three metrics.
        val valueBlock = yaml.substringAfter("  signaltometrics:").substringBefore("  spanmetrics:")
        assertThat(Regex("- key: (\\S+)").findAll(valueBlock).map { it.groupValues[1] }.toList())
            .containsOnly("gc_name", "dropped_type")
    }

    @Test
    fun `severity is counted by number, not by the text each library happens to use`() {
        // Cassandra logs WARN; the AxonOps agent on the same node logs WARNING. Conditioning on the
        // text would split one level across two series and undercount both.
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))

        assertThat(yaml).contains("severity_number >= SEVERITY_NUMBER_WARN")
    }

    @Test
    fun `the loggers worth their own counter each have one`() {
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))

        assertThat(yaml).contains("cassandra.log.gc_events")
        assertThat(yaml).contains("cassandra.log.status_dumps")
        assertThat(yaml).contains("cassandra.log.dropped_message_reports")
    }

    @Test
    fun `dropped messages are read from the logger that actually emits them`() {
        // MessagingMetrics, confirmed against real lines. An earlier condition on MessagingService
        // and NoSpamLogger - both plausible, both wrong - matched nothing at all while looking
        // exactly like a cluster that never drops a message. Nothing errors in that state, which is
        // why the logger name is pinned rather than described.
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))

        // Settings only: the config explains at length why the other two names are wrong, and
        // names them while doing it.
        val settings = yaml.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")

        assertThat(settings).contains("MessagingMetrics")
        assertThat(settings).doesNotContain("NoSpamLogger")
        assertThat(settings).doesNotContain("MessagingService")
    }

    @Test
    fun `dropped messages split internal from cross-node, with the verb as the only label`() {
        // The JMX counter says how many dropped. Only this line says of what type, and separates a
        // node dropping its own work from a peer's messages dying in transit.
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))

        assertThat(yaml).contains("cassandra.log.dropped_messages_internal")
        assertThat(yaml).contains("cassandra.log.dropped_messages_cross_node")
        assertThat(yaml).contains("cassandra.log.dropped_message_internal_latency_seconds")
        assertThat(yaml).contains("cassandra.log.dropped_message_cross_node_latency_seconds")
        assertThat(yaml).contains("- key: dropped_type")

        // Counts are summed, latencies distributed - a count per 5s window adds up to a total,
        // while a mean latency only means something as a distribution.
        assertThat(yaml).contains("value: Double(attributes[\"dropped_cross_node\"])")
        assertThat(yaml).contains("value: Double(attributes[\"dropped_cross_node_ms\"]) / 1000")
    }

    @Test
    fun `every parsed number tolerates a thousands separator`() {
        // Cassandra prints "in 520,866ms" and "31,471 internal". A [0-9]+ capture does not fail
        // loudly on those: it matches nothing and drops exactly the largest events. Every numeric
        // capture allows a comma, and every one is stripped before conversion.
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))
        val captures = Regex("\\(\\?P<(\\w+)>\\[0-9([^\\]]*)\\]").findAll(yaml).toList()

        assertThat(captures).isNotEmpty()
        assertThat(captures).allSatisfy { match ->
            assertThat(match.groupValues[2])
                .describedAs("numeric capture ${match.groupValues[1]} must allow a comma")
                .contains(",")
        }
        // And each one is stripped rather than merely tolerated.
        captures.forEach { match ->
            assertThat(yaml).contains("replace_pattern(attributes[\"${match.groupValues[1]}\"], \",\", \"\")")
        }
    }

    @Test
    fun `the log-derived metrics reach the metrics exporter`() {
        // Three things have to line up or the metrics are built and thrown away: the logs pipeline
        // must export to the connector, a metrics pipeline must receive from it, and the transform
        // that supplies the grouping attributes must run before the export.
        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))
        val logsPipeline = pipeline("logs/otlp:")
        val metricsFromLogs = pipeline("metrics/logs:")

        assertThat(logsPipeline).contains("count")
        assertThat(metricsFromLogs).contains("receivers: [count, signaltometrics]")
        assertThat(metricsFromLogs).contains("prometheusremotewrite")

        val processors = logsPipeline.substringAfter("processors:").substringBefore("exporters:")
        assertThat(processors).contains("transform/log_metric_labels")
        assertThat(yaml).contains("set(attributes[\"logger\"], instrumentation_scope.name)")
    }

    @Test
    fun `buildConfigMap with empty list contains all static scrape jobs`() {
        val configMap = builder.buildConfigMap(emptyList())
        val yaml = yamlFrom(configMap)

        assertThat(yaml).contains("job_name: 'beyla'")
        assertThat(yaml).contains("job_name: 'ebpf-exporter'")
        assertThat(yaml).contains("job_name: 'yace'")
        assertThat(yaml).contains("job_name: \"kube-state-metrics\"")
    }

    @Test
    fun `buildConfigMap with empty list does not leave placeholder in output`() {
        val configMap = builder.buildConfigMap(emptyList())
        val yaml = yamlFrom(configMap)

        assertThat(yaml).doesNotContain("__WORKLOAD_SCRAPE_JOBS__")
        assertThat(yaml).doesNotContain("__KIT_SCRAPE_JOBS__")
        assertThat(yaml).doesNotContain("__INFRA_SCRAPE_JOBS__")
    }

    /**
     * kube-state-metrics is one pod on the control node, in the pod network. A static Service
     * target would have every collector in the DaemonSet scrape the same series under its own
     * `instance`; pod discovery filtered to the collector's own node gives exactly one scraper.
     */
    @Test
    fun `kube-state-metrics is scraped through node-local pod discovery, not a static target`() {
        val job = builder.buildInfraScrapeJobs(CniMode.Flannel).single { it.jobName == "kube-state-metrics" }

        assertThat(job.staticConfigs).isNull()
        assertThat(job.kubernetesSdConfigs?.single()?.role).isEqualTo("pod")
        assertThat(
            job.kubernetesSdConfigs
                ?.single()
                ?.namespaces
                ?.names,
        ).containsExactly("default")
        val keeps = job.relabelConfigs.filter { it.action == "keep" }
        assertThat(keeps.map { it.sourceLabels?.single() to it.regex }).containsExactly(
            "__meta_kubernetes_pod_label_app_kubernetes_io_name" to "kube-state-metrics",
            "__meta_kubernetes_pod_node_name" to "\${env:HOSTNAME}",
            "__meta_kubernetes_pod_container_port_number" to "8080",
        )
        val address = job.relabelConfigs.single { it.targetLabel == "__address__" }
        assertThat(address.replacement).isEqualTo("\$\$1:8080")

        val yaml = yamlFrom(builder.buildConfigMap(emptyList()))
        assertThat(yaml).contains("job_name: \"kube-state-metrics\"")
        assertThat(yaml).doesNotContain("kube-state-metrics.default.svc")
        // Rendered at the depth of the static jobs under scrape_configs.
        assertThat(yaml).contains("\n        - job_name: \"kube-state-metrics\"")
    }

    /**
     * Flannel has no Cilium agent, operator, or Hubble. Rendering their jobs anyway would have
     * every collector poll three dead ports for the life of every default cluster.
     */
    @Test
    fun `buildConfigMap renders no Cilium scrape jobs on a Flannel cluster`() {
        val yaml = yamlFrom(builder.buildConfigMap(emptyList(), cni = CniMode.Flannel))

        assertThat(yaml).doesNotContain("cilium-agent")
        assertThat(yaml).doesNotContain("cilium-operator")
        assertThat(yaml).doesNotContain("job_name: \"hubble\"")
        assertThat(yaml).doesNotContain("localhost:9962")
        assertThat(yaml).doesNotContain("localhost:9965")
        assertThat(builder.buildCniScrapeJobs(CniMode.Flannel)).isEmpty()
    }

    @Test
    fun `buildConfigMap defaults to the Flannel rendering when no CNI is given`() {
        assertThat(yamlFrom(builder.buildConfigMap(emptyList())))
            .isEqualTo(yamlFrom(builder.buildConfigMap(emptyList(), cni = CniMode.Flannel)))
    }

    @Test
    fun `buildConfigMap on a Cilium cluster scrapes the agent and Hubble at localhost on every node`() {
        val yaml = yamlFrom(builder.buildConfigMap(emptyList(), cni = CniMode.Cilium))

        assertThat(yaml).contains("job_name: \"cilium-agent\"")
        assertThat(yaml).contains("localhost:9962")
        assertThat(yaml).contains("replacement: \"\${env:HOSTNAME}:9962\"")
        assertThat(yaml).contains("job_name: \"hubble\"")
        assertThat(yaml).contains("localhost:9965")
        assertThat(yaml).contains("replacement: \"\${env:HOSTNAME}:9965\"")
        // Every job carries the cluster label, as the static infrastructure jobs do.
        assertThat(yaml).contains("replacement: \"\${env:CLUSTER_NAME}\"")
        // The jobs land under the prometheus receiver's scrape_configs, at the same depth as the
        // static jobs, so the collector parses them as part of that list.
        assertThat(yaml).contains("\n        - job_name: \"cilium-agent\"")
    }

    /**
     * The operator runs on exactly one node, so a `localhost` target would fail everywhere but
     * there. Pod discovery in kube-system on the chart's `io.cilium/app=operator` label, filtered
     * to the collector's own node and to the metrics port, scrapes it once.
     */
    @Test
    fun `buildConfigMap on a Cilium cluster discovers the operator by pod label on its own node`() {
        val jobs = builder.buildCniScrapeJobs(CniMode.Cilium)
        val operator = jobs.single { it.jobName == "cilium-operator" }

        assertThat(operator.staticConfigs).isNull()
        assertThat(operator.kubernetesSdConfigs).hasSize(1)
        assertThat(operator.kubernetesSdConfigs?.single()?.role).isEqualTo("pod")
        assertThat(
            operator.kubernetesSdConfigs
                ?.single()
                ?.namespaces
                ?.names,
        ).containsExactly("kube-system")

        val keeps = operator.relabelConfigs.filter { it.action == "keep" }
        assertThat(keeps.map { it.sourceLabels?.single() to it.regex }).containsExactly(
            "__meta_kubernetes_pod_label_io_cilium_app" to "operator",
            "__meta_kubernetes_pod_node_name" to "\${env:HOSTNAME}",
            "__meta_kubernetes_pod_container_port_number" to "9963",
        )
        val address = operator.relabelConfigs.single { it.targetLabel == "__address__" }
        assertThat(address.sourceLabels).containsExactly("__meta_kubernetes_pod_ip")
        assertThat(address.replacement).isEqualTo("\$\$1:9963")
        val instance = operator.relabelConfigs.single { it.targetLabel == "instance" }
        assertThat(instance.sourceLabels).containsExactly("__meta_kubernetes_pod_name")

        assertThat(yamlFrom(builder.buildConfigMap(emptyList(), cni = CniMode.Cilium)))
            .contains("job_name: \"cilium-operator\"")
            .doesNotContain("localhost:9963")
    }

    @Test
    fun `Cilium scrape jobs and kit scrape jobs coexist in one rendering`() {
        val kits = listOf(WorkloadScrapeConfig(kitName = "clickhouse", jobName = "clickhouse", port = 9363, path = "/metrics"))

        val yaml = yamlFrom(builder.buildConfigMap(kits, cni = CniMode.Cilium))

        assertThat(yaml).contains("job_name: \"cilium-agent\"")
        assertThat(yaml).contains("job_name: \"clickhouse-clickhouse\"")
        assertThat(yaml).contains("job_name: \"kube-state-metrics\"")
    }

    @Test
    fun `buildConfigMap injects dynamic scrape job for each workload`() {
        val scrapeConfigs =
            listOf(
                WorkloadScrapeConfig(kitName = "presto", jobName = "presto", port = 8081, path = "/metrics"),
                WorkloadScrapeConfig(kitName = "opensearch", jobName = "opensearch", port = 9600, path = "/_prometheus/metrics"),
            )

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = yamlFrom(configMap)

        assertThat(yaml).contains("job_name: \"presto-presto\"")
        assertThat(yaml).contains("localhost:8081")
        assertThat(yaml).contains("metrics_path: \"/metrics\"")
        assertThat(yaml).contains("job_name: \"opensearch-opensearch\"")
        assertThat(yaml).contains("localhost:9600")
        assertThat(yaml).contains("metrics_path: \"/_prometheus/metrics\"")
        // Without a pod-selector, targets are static NodePorts — no pod discovery.
        assertThat(jobBlock(yaml, "presto-presto")).contains("static_configs").doesNotContain("kubernetes_sd_configs")
        assertThat(jobBlock(yaml, "opensearch-opensearch")).contains("static_configs").doesNotContain("kubernetes_sd_configs")
    }

    /** The lines of one rendered scrape job, from its `job_name` up to the next job or the end of the list. */
    private fun jobBlock(
        yaml: String,
        jobName: String,
    ): String {
        val lines = yaml.lines()
        val start = lines.indexOfFirst { it.trim() == "- job_name: \"$jobName\"" }
        check(start >= 0) { "scrape job $jobName is not in the rendered config" }
        return lines
            .drop(start + 1)
            .takeWhile { !it.trim().startsWith("- job_name:") && it.startsWith("          ") }
            .joinToString("\n")
    }

    @Test
    fun `buildConfigMap generates pod service discovery job when podSelector is set`() {
        val scrapeConfigs =
            listOf(
                WorkloadScrapeConfig(
                    kitName = "tidb",
                    jobName = "tikv",
                    port = 20180,
                    path = "/metrics",
                    podSelector = "app.kubernetes.io/component=tikv,app.kubernetes.io/instance=tidb",
                ),
            )

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = yamlFrom(configMap)

        assertThat(yaml).contains("job_name: \"tidb-tikv\"")
        // Uses pod service discovery, not a static localhost NodePort target.
        assertThat(yaml).contains("kubernetes_sd_configs")
        assertThat(yaml).contains("role: \"pod\"")
        assertThat(yaml).doesNotContain("localhost:20180")
        // Selector becomes keep relabels on sanitized pod meta-labels.
        assertThat(yaml).contains("__meta_kubernetes_pod_label_app_kubernetes_io_component")
        assertThat(yaml).contains("__meta_kubernetes_pod_label_app_kubernetes_io_instance")
        assertThat(yaml).contains("action: \"keep\"")
        // Node-local filtering so each collector scrapes only its own node's pod (no duplicate series).
        assertThat(yaml).contains("__meta_kubernetes_pod_node_name")
        assertThat(yaml).contains("\${env:HOSTNAME}")
        // instance = pod name — the per-store identity, sourced from the pod name meta-label.
        assertThat(yaml).contains("__meta_kubernetes_pod_name")
        assertThat(yaml).contains("target_label: \"instance\"")
        // Scrape the pod IP on the container metrics port; $$ escapes to a literal $ for Prometheus.
        assertThat(yaml).contains("__meta_kubernetes_pod_ip")
        assertThat(yaml).contains("\$\$1:20180")
        // Logical job label preserved.
        assertThat(yaml).contains("target_label: \"job\"")
        assertThat(yaml).contains("replacement: \"tikv\"")
    }

    @Test
    fun `buildConfigMap uses kitName-jobName compound key as OTel job_name and relabels job to jobName`() {
        val scrapeConfigs =
            listOf(
                WorkloadScrapeConfig(kitName = "postgres-duckdb", jobName = "postgres", port = 30987, path = "/metrics"),
                WorkloadScrapeConfig(kitName = "postgres-postgis", jobName = "postgres", port = 30988, path = "/metrics"),
            )

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = yamlFrom(configMap)

        // OTel job_name is kitName-jobName compound to ensure uniqueness for both:
        // - multiple targets per kit (e.g. kafka-exporter, kafka-jmx)
        // - multiple kit instances with the same jobName (e.g. postgres-duckdb, postgres-postgis)
        assertThat(yaml).contains("job_name: \"postgres-duckdb-postgres\"")
        assertThat(yaml).contains("job_name: \"postgres-postgis-postgres\"")
        // Relabel sets the `job` label in metrics back to the logical jobName
        assertThat(yaml).contains("target_label: \"job\"").contains("replacement: \"postgres\"")
    }

    @Test
    fun `buildConfigMap with dynamic jobs still contains all static scrape jobs`() {
        val scrapeConfigs = listOf(WorkloadScrapeConfig(kitName = "clickhouse", jobName = "clickhouse", port = 9363, path = "/metrics"))

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = yamlFrom(configMap)

        assertThat(yaml).contains("job_name: 'beyla'")
        assertThat(yaml).contains("job_name: 'ebpf-exporter'")
        assertThat(yaml).contains("job_name: 'yace'")
        assertThat(yaml).contains("job_name: \"kube-state-metrics\"")
        assertThat(yaml).contains("job_name: \"clickhouse-clickhouse\"")
    }

    @Test
    fun `buildConfigMap dynamic jobs use OTel runtime env expansion not placeholder`() {
        val scrapeConfigs = listOf(WorkloadScrapeConfig(kitName = "mydb", jobName = "mydb", port = 9999, path = "/metrics"))

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = yamlFrom(configMap)

        assertThat(yaml).contains("\${env:HOSTNAME}:9999")
        assertThat(yaml).contains("\${env:CLUSTER_NAME}")
    }

    @Test
    fun `buildConfigMap has correct metadata`() {
        val configMap = builder.buildConfigMap(emptyList())

        assertThat(configMap.metadata.name).isEqualTo("otel-collector-config")
        assertThat(configMap.metadata.namespace).isEqualTo("default")
        assertThat(configMap.data).containsKey("otel-collector-config.yaml")
    }

    @Test
    fun `buildConfigMap with username generates basic_auth block in scrape job`() {
        val scrapeConfigs =
            listOf(
                WorkloadScrapeConfig(kitName = "trino", jobName = "trino", port = 8080, path = "/metrics", username = "trino"),
            )

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = yamlFrom(configMap)

        assertThat(yaml).contains("basic_auth:")
        assertThat(yaml).contains("username: \"trino\"")
    }

    /** Trino's coordinator is found by pod discovery and still needs its basic-auth user. */
    @Test
    fun `a pod-discovered scrape job keeps its basic_auth user`() {
        val scrapeConfigs =
            listOf(
                WorkloadScrapeConfig(
                    kitName = "trino",
                    jobName = "trino",
                    port = 8080,
                    path = "/metrics",
                    username = "trino",
                    podSelector = "app.kubernetes.io/name=trino,app.kubernetes.io/component=coordinator",
                ),
            )

        val job = jobBlock(yamlFrom(builder.buildConfigMap(scrapeConfigs)), "trino-trino")

        assertThat(job).contains("kubernetes_sd_configs").doesNotContain("static_configs").doesNotContain("localhost:8080")
        assertThat(job).contains("basic_auth:").contains("username: \"trino\"")
    }

    @Test
    fun `buildConfigMap without username omits basic_auth block`() {
        val scrapeConfigs =
            listOf(
                WorkloadScrapeConfig(kitName = "clickhouse", jobName = "clickhouse", port = 9363, path = "/metrics"),
            )

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = yamlFrom(configMap)

        assertThat(yaml).doesNotContain("basic_auth:")
    }

    /**
     * AC4 guard: with no redirect the three export endpoints must resolve to the in-cluster
     * backend services, exactly as the config shipped before the redirect placeholders were
     * introduced. If a substitution key ever drifts from its literal, a placeholder would leak
     * into the rendered config and the collector would fail to start.
     */
    @Test
    fun `local mode ConfigMap exports to the in-cluster backend services`() {
        val yaml = yamlFrom(builder.buildConfigMap(emptyList(), telemetryRedirect = null))

        assertThat(yaml).contains("http://victoriametrics.default.svc.cluster.local:8428/api/v1/write")
        assertThat(yaml).contains("http://victorialogs.default.svc.cluster.local:9428/insert/opentelemetry")
        assertThat(yaml).contains("tempo.default.svc.cluster.local:4320")
        // No unresolved endpoint placeholders may survive substitution.
        assertThat(yaml).doesNotContain("__VICTORIAMETRICS_ENDPOINT__")
        assertThat(yaml).doesNotContain("__VICTORIALOGS_ENDPOINT__")
        assertThat(yaml).doesNotContain("__TEMPO_ENDPOINT__")
    }

    /**
     * Redirect mode replaces the three in-cluster export endpoints with the external stack's
     * endpoints derived from the base host. Traces must target Tempo's OTLP receiver port 4320,
     * never its query port 3200 — sending OTLP to 3200 silently drops every span.
     */
    @Test
    fun `redirect mode ConfigMap exports to the external stack endpoints`() {
        val redirect = TelemetryRedirect.fromBaseHost("10.0.0.9")

        val yaml = yamlFrom(builder.buildConfigMap(emptyList(), telemetryRedirect = redirect))

        assertThat(yaml).contains("http://10.0.0.9:8428/api/v1/write")
        assertThat(yaml).contains("http://10.0.0.9:9428/insert/opentelemetry")
        assertThat(yaml).contains("10.0.0.9:4320")
        // Redirect must displace the in-cluster services entirely, not merely add alongside them.
        assertThat(yaml).doesNotContain("victoriametrics.default.svc.cluster.local")
        assertThat(yaml).doesNotContain("victorialogs.default.svc.cluster.local")
        assertThat(yaml).doesNotContain("tempo.default.svc.cluster.local")
        // Traces never target the query port.
        assertThat(yaml).doesNotContain("10.0.0.9:3200")
    }
}
