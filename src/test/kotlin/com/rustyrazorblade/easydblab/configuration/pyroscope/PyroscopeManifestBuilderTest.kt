package com.rustyrazorblade.easydblab.configuration.pyroscope

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.YamlTestSupport.scalarAt
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for PyroscopeManifestBuilder.
 *
 * Uses real TemplateService (never mocked per project convention) to verify
 * config file loading from classpath resources.
 */
class PyroscopeManifestBuilderTest : BaseKoinTest() {
    private companion object {
        /**
         * The largest node in use: c8gd.48xlarge, 192 vCPUs. easy-db-lab keeps no instance-type table;
         * `--db.instance-type` takes any EC2 type, so raise this and the limit together for a larger one.
         */
        const val LARGEST_NODE_VCPUS = 192L

        /** The sample count Pyroscope rejected on edbl-ab1172 on 2026-11-07, over the old 16,000 limit. */
        const val MEASURED_SAMPLES = 18_995L

        /**
         * The encoded size budget for one sample: its packed location ids, value and share of the
         * location, function and string tables. A 19,000-sample pprof with 60-frame stacks is 226 bytes
         * a sample; edbl-ab1172's 18,995-sample profile fit the old 4 MiB limit, so under 221.
         */
        const val BYTES_PER_SAMPLE = 256L

        const val MIB = 1024L * 1024L

        /** Pyroscope 2.3.1's single-binary gRPC message cap (`server.grpc-max-recv-msg-size-bytes`). */
        const val PYROSCOPE_GRPC_MAX_MESSAGE_BYTES = 100L * MIB
    }

    private lateinit var builder: PyroscopeManifestBuilder
    private lateinit var templateService: TemplateService
    private lateinit var mockClusterStateManager: ClusterStateManager

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
            ClusterState(
                name = "test-cluster",
                hosts = mutableMapOf(),
                s3Bucket = "acct-bucket",
                dataBucket = "easy-db-lab-data-abc",
                initConfig = InitConfig(tenant = "acme"),
            ),
        )
        templateService = getKoin().get()
        builder = PyroscopeManifestBuilder(templateService)
    }

    @Test
    fun `buildServerDeployment has no init container`() {
        val deployment = builder.buildServerDeployment()

        assertThat(deployment.spec.template.spec.initContainers).isNullOrEmpty()
    }

    @Test
    fun `the server's startup budget outlasts Pyroscope 2's fixed readiness waits`() {
        val container =
            builder
                .buildServerDeployment()
                .spec.template.spec.containers
                .single { it.image == PyroscopeManifestBuilder.SERVER_IMAGE }
        val startup = container.startupProbe

        assertThat(startup).isNotNull
        assertThat(startup.httpGet.path).isEqualTo("/ready")
        assertThat(startup.httpGet.port.intVal).isEqualTo(PyroscopeManifestBuilder.SERVER_PORT)
        // Pyroscope 2.3.1 answers /ready with 503 for the metastore's 15 s min-ready wait and then
        // the segment writer's 30 s one, back to back: 48 s measured on an idle machine. The
        // kubelet must not kill it inside that window, and a busy control node is slower.
        val budgetSeconds = (startup.initialDelaySeconds ?: 0) + startup.periodSeconds * startup.failureThreshold
        assertThat(budgetSeconds).isGreaterThanOrEqualTo(3 * 48)
    }

    @Test
    fun `eBPF config discovers Kubernetes pods and derives per-pod attribution labels`() {
        val configMap = builder.buildEbpfConfigMap()
        val config = configMap.data.getValue("config.alloy")

        // Discovers pods on the local node so processes can be attributed.
        assertThat(config).contains("discovery.kubernetes \"pods\"")
        assertThat(config).contains("spec.nodeName=")
        // Joins host processes to pod metadata by container id.
        assertThat(config).contains("join")
        assertThat(config).contains("discovery.relabel.kubernetes_pods.output")
        // Produces the dimensions Pyroscope filters on.
        assertThat(config).contains("target_label  = \"service_name\"")
        assertThat(config).contains("target_label  = \"pod\"")
        assertThat(config).contains("target_label  = \"container\"")
        assertThat(config).contains("__meta_kubernetes_pod_container_name")
    }

    @Test
    fun `eBPF DaemonSet uses the pod-reading ServiceAccount`() {
        val daemonSet = builder.buildEbpfDaemonSet()

        assertThat(daemonSet.spec.template.spec.serviceAccountName).isEqualTo("pyroscope-ebpf")
    }

    @Test
    fun `eBPF ClusterRole grants read access to pods for discovery`() {
        val clusterRole = builder.buildEbpfClusterRole()

        val podRule =
            clusterRole.rules.single { rule ->
                rule.resources.contains("pods")
            }
        assertThat(podRule.verbs).contains("get", "list", "watch")
    }

    private fun serverConfig(): String = builder.buildServerConfigMap().data.getValue("config.yaml")

    @Test
    fun `the server runs native multi-tenancy on pure v2 storage`() {
        val config = serverConfig()

        assertThat(scalarAt(config, "multitenancy_enabled")).isEqualTo("true")
        assertThat(scalarAt(config, "architecture_storage")).isEqualTo("v2")
    }

    @Test
    fun `nothing is ever deleted by age`() {
        val config = serverConfig()

        assertThat(scalarAt(config, "metastore", "index", "cleanup_interval")).isEqualTo("0s")
        assertThat(scalarAt(config, "limits", "retention_period")).isEqualTo("0s")
    }

    @Test
    fun `no fixed wait holds the server open after SIGTERM, where it refuses every push`() {
        val config = serverConfig()

        assertThat(scalarAt(config, "metastore", "min_ready_duration")).isEqualTo("0s")
        assertThat(scalarAt(config, "segment_writer", "lifecycler", "min_ready_duration")).isEqualTo("0s")
        assertThat(scalarAt(config, "analytics", "reporting_enabled")).isEqualTo("false")
    }

    @Test
    fun `the Service sends every client to the ingest front, which forwards to the server on the pod's loopback`() {
        val port =
            builder
                .buildServerService()
                .spec.ports
                .single()
        val front =
            builder
                .buildServerDeployment()
                .spec.template.spec.containers
                .single { it.image == Constants.PyroscopeServer.FRONT_IMAGE }
        val frontConfig = builder.buildFrontConfig().lines().map { it.trim() }

        assertThat(port.port).isEqualTo(PyroscopeManifestBuilder.SERVER_PORT)
        assertThat(port.targetPort.intVal).isEqualTo(Constants.PyroscopeServer.FRONT_PORT)
        assertThat(front.ports.map { it.containerPort }).containsExactly(Constants.PyroscopeServer.FRONT_PORT)
        assertThat(frontConfig).contains(
            "bind :${Constants.PyroscopeServer.FRONT_PORT}",
            "server pyroscope 127.0.0.1:${PyroscopeManifestBuilder.SERVER_PORT}",
        )
        assertThat(front.args.last()).endsWith("/${PyroscopeManifestBuilder.FRONT_CONFIG_KEY}")
        assertThat(builder.buildServerConfigMap().data).containsKey(PyroscopeManifestBuilder.FRONT_CONFIG_KEY)
    }

    @Test
    fun `the front rewrites only the shutdown 422, and its image is pinned by digest`() {
        val rules =
            builder
                .buildFrontConfig()
                .lines()
                .map { it.trim() }
                .filter { it.startsWith("http-response") }

        assertThat(rules).containsExactly(
            "http-response wait-for-body time 1s if { status 422 }",
            "http-response set-status 503 if { status 422 } { res.body -m reg $SHUTDOWN_REFUSAL_PATTERN }",
        )
        assertThat(Constants.PyroscopeServer.FRONT_IMAGE).matches("haproxy:[0-9.]+-alpine@sha256:[0-9a-f]{64}")
    }

    @Test
    fun `the front's refusal pattern matches only Pyroscope's shutdown body, not the phrase elsewhere in a 422`() {
        val rule =
            builder
                .buildFrontConfig()
                .lines()
                .map { it.trim() }
                .single { it.startsWith("http-response set-status 503") }
        // HAProxy's `-m reg` is a POSIX extended regex; Java's matches these patterns the same way.
        val pattern = Regex(rule.substringAfter("-m reg '").substringBeforeLast("' }").replace("[[:space:]]", "\\s"))
        val shutdown =
            """{"code":"unknown","message":"pushing IngestInput-pprof failed push series with index 0 and id  failed: """ +
                """unknown: rpc error: code = Unavailable desc = service is unavailable"}"""
        val parseError = """{"code":"invalid_argument","message":"failed to parse profile: frame service is unavailable in stack"}"""
        val laterPhrase = """{"code":"unknown","message":"rpc error: code = Unavailable desc = service is unavailable; and then more"}"""

        assertThat(pattern.containsMatchIn(shutdown)).describedAs("Pyroscope's shutdown refusal").isTrue()
        assertThat(pattern.containsMatchIn("$shutdown\n")).describedAs("the refusal with a trailing newline").isTrue()
        assertThat(pattern.containsMatchIn(parseError)).describedAs("a parse error naming the phrase").isFalse()
        assertThat(pattern.containsMatchIn(laterPhrase)).describedAs("the gRPC error not at the body's end").isFalse()
    }

    @Test
    fun `a change to the front's config rolls the server pod`() {
        fun hashWith(frontConfig: String): String? {
            val resources =
                builder.buildServerResources().map { resource ->
                    if (resource is ConfigMap && PyroscopeManifestBuilder.FRONT_CONFIG_KEY in resource.data.orEmpty()) {
                        ConfigMapBuilder(resource).addToData(PyroscopeManifestBuilder.FRONT_CONFIG_KEY, frontConfig).build()
                    } else {
                        resource
                    }
                }
            return ConfigHashAnnotator
                .annotate(resources)
                .filterIsInstance<Deployment>()
                .single()
                .spec.template.metadata.annotations[Constants.K8s.CONFIG_HASH_ANNOTATION]
        }
        val rendered = builder.buildFrontConfig()

        assertThat(hashWith(rendered)).isEqualTo(hashWith(rendered))
        assertThat(hashWith("$rendered\n# changed\n")).isNotEqualTo(hashWith(rendered))
    }

    @Test
    fun `the rendered front config has no placeholder left`() {
        assertThat(Regex("__[A-Z_]+__").findAll(builder.buildFrontConfig()).map { it.value }.toList()).isEmpty()
    }

    @Test
    fun `a front that is down takes the pod out of the Service, and a starting server never restarts it`() {
        val front =
            builder
                .buildServerDeployment()
                .spec.template.spec.containers
                .single { it.name == Constants.PyroscopeServer.FRONT_NAME }

        assertThat(front.readinessProbe.httpGet.path).isEqualTo("/ready")
        assertThat(front.readinessProbe.httpGet.port.intVal).isEqualTo(Constants.PyroscopeServer.FRONT_PORT)
        assertThat(front.livenessProbe.httpGet).describedAs("liveness must not follow the server's /ready").isNull()
        assertThat(front.livenessProbe.tcpSocket.port.intVal).isEqualTo(Constants.PyroscopeServer.FRONT_PORT)
    }

    @Test
    fun `profiles are stored under the observability profiles prefix of the account bucket, not the data bucket`() {
        val config = serverConfig()

        assertThat(scalarAt(config, "storage", "backend")).isEqualTo("s3")
        assertThat(scalarAt(config, "storage", "s3", "bucket_name")).isEqualTo("acct-bucket")
        assertThat(scalarAt(config, "storage", "prefix")).isEqualTo("pyroscope")
        assertThat(config).doesNotContain("easy-db-lab-data-abc")
    }

    @Test
    fun `the metastore index lives on the control node's disk`() {
        val config = serverConfig()
        val pod =
            builder
                .buildServerDeployment()
                .spec.template.spec
        val volume = pod.volumes.single { it.name == "data" }
        val mount = pod.containers[0].volumeMounts.single { it.name == "data" }

        assertThat(volume.hostPath.path).isEqualTo(PyroscopeManifestBuilder.DATA_HOST_PATH)
        assertThat(scalarAt(config, "metastore", "data_dir")).startsWith(mount.mountPath + "/")
        assertThat(scalarAt(config, "metastore", "raft", "dir")).startsWith(mount.mountPath + "/")
        assertThat(scalarAt(config, "metastore", "raft", "snapshots_dir")).startsWith(mount.mountPath + "/")
        // Without its tokens a restarted segment writer refuses every push for about 5.5s (issue 991).
        assertThat(scalarAt(config, "segment_writer", "lifecycler", "tokens_file_path")).startsWith(mount.mountPath + "/")
    }

    /** The limit at [key] under `limits` in the server config, as a number. */
    private fun limit(key: String): Long = requireNotNull(scalarAt(serverConfig(), "limits", key)) { "limits.$key is not set" }.toLong()

    /** An integer setting `name = value` in the eBPF agent's config.alloy. */
    private fun alloySetting(name: String): Long {
        val alloy = builder.buildEbpfConfigMap().data.getValue("config.alloy")
        val match = requireNotNull(Regex("""\b$name\s*=\s*"?(\d+)""").find(alloy)) { "config.alloy sets no $name" }
        return match.groupValues[1].toLong()
    }

    @Test
    fun `a profile from a saturated large node fits the sample limit`() {
        // One eBPF profile holds at most one sample per CPU tick in its window: the agent's
        // sample_rate times its collect_interval times the node's vCPUs. edbl-ab1172 sent 18,995.
        val maxSamplesPerProfile = alloySetting("sample_rate") * alloySetting("collect_interval") * LARGEST_NODE_VCPUS

        assertThat(limit("max_profile_stacktrace_samples"))
            .isGreaterThanOrEqualTo(maxSamplesPerProfile)
            .isGreaterThanOrEqualTo(MEASURED_SAMPLES * 10)
    }

    @Test
    fun `a profile at the sample limit fits the size limit, the burst, and the internal gRPC message`() {
        val sizeLimit = limit("max_profile_size_bytes")

        assertThat(sizeLimit).isGreaterThanOrEqualTo(limit("max_profile_stacktrace_samples") * BYTES_PER_SAMPLE)
        // The distributor's token bucket rejects a push bigger than the burst, whatever its rate.
        assertThat(limit("ingestion_burst_size_mb") * MIB).isGreaterThanOrEqualTo(sizeLimit)
        // The distributor forwards each profile to the segment writer in one gRPC message.
        assertThat(sizeLimit).isLessThan(PYROSCOPE_GRPC_MAX_MESSAGE_BYTES)
    }

    @Test
    fun `the profiles relay listens on the node's loopback at the relay port and forwards to the agent's writer`() {
        val alloy = builder.buildEbpfConfigMap().data.getValue("config.alloy")
        val relay = requireNotNull(Regex("""pyroscope\.receive_http "relay" \{(.*?)\n}""", RegexOption.DOT_MATCHES_ALL).find(alloy))

        assertThat(relay.groupValues[1])
            .contains("listen_address = \"127.0.0.1\"")
            .contains("forward_to = [pyroscope.write.endpoint.receiver]")
        assertThat(alloySetting("listen_port")).isEqualTo(Constants.K8s.ALLOY_RELAY_PORT.toLong())
    }

    @Test
    fun `the eBPF agent sends the cluster's tenant on every write`() {
        val alloy = builder.buildEbpfConfigMap().data.getValue("config.alloy")
        val env =
            builder
                .buildEbpfDaemonSet()
                .spec.template.spec.containers[0]
                .env

        assertThat(alloy).contains("headers = {")
        assertThat(alloy).contains("\"X-Scope-OrgID\" = sys.env(\"TENANT\")")
        val tenant = env.single { it.name == "TENANT" }.valueFrom.configMapKeyRef
        assertThat(tenant.name).isEqualTo("cluster-config")
        assertThat(tenant.key).isEqualTo("tenant")
    }
}

/** The front's match for Pyroscope's shutdown refusal: the wrapped gRPC error that ends its JSON body. */
private const val SHUTDOWN_REFUSAL_PATTERN =
    "'rpc error: code = Unavailable desc = service is unavailable\"}[[:space:]]*\$'"
