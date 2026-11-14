package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.YamlTestSupport.keysAt
import com.rustyrazorblade.easydblab.YamlTestSupport.listAt
import com.rustyrazorblade.easydblab.YamlTestSupport.scalarAt
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The host add-on collector: what only a privileged, host-network pod can read, forwarded to the
 * core collector and nowhere else.
 */
class OtelHostCollectorManifestBuilderTest : BaseKoinTest() {
    private lateinit var builder: OtelHostCollectorManifestBuilder

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager>() }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        whenever(getKoin().get<ClusterStateManager>().load()).thenReturn(ClusterState(name = "test", versions = mutableMapOf()))
        builder = OtelHostCollectorManifestBuilder(getKoin().get())
    }

    private fun yamlFrom(configMap: ConfigMap): String = checkNotNull(configMap.data["otel-host-collector-config.yaml"])

    private fun config(cni: CniMode = CniMode.Flannel) = yamlFrom(builder.buildConfigMap(cni))

    private fun container() =
        builder
            .buildDaemonSet()
            .spec.template.spec.containers
            .single()

    /** Every pipeline ends at the core; a backend exporter here would skip its tenant and its counters. */
    @Test
    fun `every pipeline forwards to the core collector and to nothing else`() {
        val yaml = config(CniMode.Cilium)

        assertThat(keysAt(yaml, "exporters")).containsExactly("otlp_grpc/core")
        assertThat(scalarAt(yaml, "exporters", "otlp_grpc/core", "endpoint")).isEqualTo("\${env:OTEL_CORE_ENDPOINT}")
        val pipelines = keysAt(yaml, "service", "pipelines")
        assertThat(pipelines).contains("metrics/otlp", "logs/otlp", "traces/otlp", "metrics/local", "logs/local")
        pipelines.forEach { name ->
            assertThat(listAt(yaml, "service", "pipelines", name, "exporters")).describedAs(name).containsExactly("otlp_grpc/core")
        }
        assertThat(keysAt(yaml, "processors")).noneMatch { it.startsWith("memory_limiter") }
        assertThat(yaml).doesNotContain("X-Scope-OrgID")

        val endpoint = container().env.single { it.name == "OTEL_CORE_ENDPOINT" }.value
        assertThat(endpoint).isEqualTo("otel-collector.default.svc:4317")
    }

    /** Host processes that are not pods keep their endpoint: the node's 4317 and 4318. */
    @Test
    fun `the add-on owns the node's OTLP listener`() {
        val yaml = config()
        assertThat(scalarAt(yaml, "receivers", "otlp", "protocols", "grpc", "endpoint")).isEqualTo("0.0.0.0:4317")
        assertThat(scalarAt(yaml, "receivers", "otlp", "protocols", "http", "endpoint")).isEqualTo("0.0.0.0:4318")

        val pod =
            builder
                .buildDaemonSet()
                .spec.template.spec
        assertThat(pod.hostNetwork).isTrue()
        assertThat(container().securityContext.privileged).isTrue()
        assertThat(container().ports.map { it.hostPort }).contains(4317, 4318)
        assertThat(pod.tolerations).anyMatch { it.operator == "Exists" && it.key == null }
        assertThat(
            builder
                .buildDaemonSet()
                .spec.template.metadata.labels,
        ).containsEntry("app.kubernetes.io/name", "otel-collector-host")
            .doesNotContainEntry("app.kubernetes.io/name", "otel-collector")
    }

    @Test
    fun `host sources carry the node's name and role`() {
        val yaml = config()
        for (pipeline in listOf("metrics/local", "logs/local")) {
            assertThat(listAt(yaml, "service", "pipelines", pipeline, "processors"))
                .describedAs(pipeline)
                .containsSubsequence("resource/host_name", "transform/node_role_from_host", "batch")
        }
        assertThat(listAt(yaml, "service", "pipelines", "metrics/local", "receivers")).containsExactly("host_metrics", "prometheus")
        assertThat(listAt(yaml, "service", "pipelines", "logs/local", "receivers"))
            .containsExactly("file_log/system", "file_log/tools", "file_log/cassandra")
        assertThat(yaml).contains("value: \${env:K8S_NODE_NAME}")
        assertThat(
            container()
                .env
                .single { it.name == "K8S_NODE_NAME" }
                .valueFrom.fieldRef.fieldPath,
        ).isEqualTo("spec.nodeName")
    }

    /**
     * The collector is a container. Without the node's root filesystem mounted and `root_path`
     * pointing at it, the hostmetrics scrapers describe the container, and the filesystem scraper
     * finds nothing worth reporting at all.
     */
    @Test
    fun `hostmetrics reads the node through a read-only host root mount`() {
        // root_path belongs to the receiver, not to a scraper.
        assertThat(config()).contains("\n    root_path: ${OtelHostCollectorManifestBuilder.HOST_ROOT_MOUNT_PATH}\n")

        val mount = container().volumeMounts.single { it.name == OtelHostCollectorManifestBuilder.HOST_ROOT_VOLUME }
        assertThat(mount.mountPath).isEqualTo(OtelHostCollectorManifestBuilder.HOST_ROOT_MOUNT_PATH)
        assertThat(mount.readOnly).isTrue()
        val volume =
            builder
                .buildDaemonSet()
                .spec.template.spec.volumes
                .single { it.name == OtelHostCollectorManifestBuilder.HOST_ROOT_VOLUME }
        assertThat(volume.hostPath.path).isEqualTo("/")
        assertThat(volume.hostPath.type).isEqualTo("Directory")
    }

    @Test
    fun `hostmetrics scrapes paging, per-core CPU and both utilization metrics, without squashfs`() {
        val yaml = config()
        assertThat(keysAt(yaml, "receivers", "host_metrics", "scrapers"))
            .contains("cpu", "disk", "load", "filesystem", "memory", "network", "paging", "processes")
        assertThat(listAt(yaml, "receivers", "host_metrics", "scrapers", "cpu", "metrics", "system.cpu.time", "attributes"))
            .contains("cpu", "state")
        assertThat(
            scalarAt(yaml, "receivers", "host_metrics", "scrapers", "filesystem", "metrics", "system.filesystem.utilization", "enabled"),
        ).isEqualTo("true")
        assertThat(scalarAt(yaml, "receivers", "host_metrics", "scrapers", "paging", "metrics", "system.paging.utilization", "enabled"))
            .isEqualTo("true")
        assertThat(listAt(yaml, "receivers", "host_metrics", "scrapers", "filesystem", "exclude_fs_types", "fs_types"))
            .containsExactly("squashfs")
    }

    /**
     * Every host file receiver sets `source` on the resource: Loki indexes it only there, and it is
     * what keeps the core's Cassandra log counters from counting these lines.
     */
    @Test
    fun `every host file receiver sets its source on the resource`() {
        val yaml = config()
        for (receiver in listOf("file_log/system", "file_log/tools", "file_log/cassandra")) {
            assertThat(scalarAt(yaml, "receivers", receiver, "resource", "source")).describedAs(receiver).isNotBlank()
            assertThat(keysAt(yaml, "receivers", receiver, "attributes")).describedAs(receiver).doesNotContain("source")
        }
    }

    /** The core reads container logs from the pod log files; the system receiver must not read them again. */
    @Test
    fun `the system file receiver skips container and pod logs`() {
        assertThat(listAt(config(), "receivers", "file_log/system", "exclude"))
            .contains("/var/log/containers/**", "/var/log/pods/**")
    }

    /** Cassandra's application logs arrive over OTLP; the file receiver tails only the JVM GC log. */
    @Test
    fun `the Cassandra file receiver tails only the JVM GC log, under its own source`() {
        val yaml = config()
        assertThat(listAt(yaml, "receivers", "file_log/cassandra", "include")).containsExactly("/mnt/db1/cassandra/logs/gc.log*")
        assertThat(listAt(yaml, "receivers", "file_log/cassandra", "exclude")).contains("/mnt/db1/cassandra/logs/gc.log*.gz")
        assertThat(scalarAt(yaml, "receivers", "file_log/cassandra", "resource", "source")).isEqualTo("cassandra-gc")
    }

    @Test
    fun `Beyla, ebpf_exporter and the add-on itself are scraped at localhost under the node's name`() {
        val yaml = config()
        for ((job, port) in listOf("beyla" to 9400, "ebpf-exporter" to 9435, "otel-collector-host" to 8888)) {
            val block = yaml.substringAfter("- job_name: '$job'").substringBefore("- job_name:")
            assertThat(block).describedAs(job).contains("localhost:$port").contains("'\${env:K8S_NODE_NAME}:$port'")
        }
        // Its own telemetry never shares the core's job name.
        assertThat(yaml).doesNotContain("job_name: 'otel-collector'\n")
    }

    /** Flannel has no Cilium agent, operator or Hubble; their jobs would poll dead ports forever. */
    @Test
    fun `Cilium scrape jobs exist only on a Cilium cluster`() {
        assertThat(builder.buildCniScrapeJobs(CniMode.Flannel)).isEmpty()
        assertThat(
            config(CniMode.Flannel),
        ).doesNotContain("job_name: \"cilium").doesNotContain("job_name: \"hubble").doesNotContainPattern("__[A-Z_]+__")

        val cilium = config(CniMode.Cilium)
        assertThat(cilium).contains("job_name: \"cilium-agent\"").contains("localhost:9962")
        assertThat(cilium).contains("job_name: \"hubble\"").contains("localhost:9965")
        assertThat(cilium).contains("replacement: \"\${env:K8S_NODE_NAME}:9962\"")
        // Rendered under the prometheus receiver's scrape_configs, at the depth of the static jobs.
        assertThat(cilium).contains("\n        - job_name: \"cilium-agent\"")
    }

    /** The operator runs on one node, so it is found by pod discovery and scraped only from there. */
    @Test
    fun `the Cilium operator is discovered by pod label on its own node`() {
        val operator = builder.buildCniScrapeJobs(CniMode.Cilium).single { it.jobName == "cilium-operator" }

        assertThat(operator.staticConfigs).isNull()
        assertThat(
            operator.kubernetesSdConfigs
                ?.single()
                ?.namespaces
                ?.names,
        ).containsExactly("kube-system")
        val keeps = operator.relabelConfigs.filter { it.action == "keep" }
        assertThat(keeps.map { it.sourceLabels?.single() to it.regex }).containsExactly(
            "__meta_kubernetes_pod_label_io_cilium_app" to "operator",
            "__meta_kubernetes_pod_node_name" to "\${env:K8S_NODE_NAME}",
            "__meta_kubernetes_pod_container_port_number" to "9963",
        )
        assertThat(operator.relabelConfigs.single { it.targetLabel == "__address__" }.replacement).isEqualTo("\$\$1:9963")
        // Pod discovery needs pod read access under the add-on's own ServiceAccount.
        assertThat(builder.buildClusterRole().rules).anyMatch { "pods" in it.resources && "list" in it.verbs }
        assertThat(
            builder
                .buildDaemonSet()
                .spec.template.spec.serviceAccountName,
        ).isEqualTo("otel-collector-host")
    }

    @Test
    fun `the add-on uses no deprecated component ID and propagates transform errors`() {
        val yaml = config(CniMode.Cilium)
        assertThat(DeprecatedCollectorComponents.findIn(yaml)).isEmpty()
        keysAt(yaml, "processors").filter { it.startsWith("transform") }.forEach {
            assertThat(scalarAt(yaml, "processors", it, "error_mode")).describedAs(it).isEqualTo("propagate")
        }
    }
}
