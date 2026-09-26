package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import io.fabric8.kubernetes.api.model.batch.v1.Job
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * Tests for DefaultStressJobService Job building.
 *
 * Verifies that buildJob includes the OTel sidecar container for metrics collection,
 * and that buildCommandJob (short-lived commands) does not include it.
 */
class DefaultStressJobServiceTest : BaseKoinTest() {
    private lateinit var service: DefaultStressJobService
    private lateinit var mockK8sService: K8sService
    private lateinit var mockEcrPullSecrets: EcrPullSecretService

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<K8sService>().also { mockK8sService = it } }
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(
                            ClusterState(
                                name = "test-cluster",
                                versions = mutableMapOf(),
                                infrastructure =
                                    InfrastructureState(
                                        vpcId = "vpc-test",
                                        region = "us-west-2",
                                    ),
                                hosts =
                                    mapOf(
                                        ServerType.Control to
                                            listOf(
                                                ClusterHost(
                                                    publicIp = "54.123.45.67",
                                                    privateIp = "10.0.1.5",
                                                    alias = "control0",
                                                    availabilityZone = "us-west-2a",
                                                    instanceId = "i-control123",
                                                ),
                                            ),
                                    ),
                            ),
                        )
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        mockK8sService = getKoin().get()
        // The default stress image is public, so no pull secret is involved.
        mockEcrPullSecrets = mock()
        whenever(mockEcrPullSecrets.ensureFor(any(), any(), any())).thenReturn("")
        val clusterStateManager: ClusterStateManager = getKoin().get()
        service =
            DefaultStressJobService(
                mockK8sService,
                clusterStateManager,
                com.rustyrazorblade.easydblab.events
                    .EventBus(),
                getKoin().get(),
                mockEcrPullSecrets,
            )
    }

    @Test
    fun `buildJob should include stress and otel-sidecar containers`() {
        val job =
            service.buildJob(
                StressJobConfig(
                    jobName = "stress-test-123",
                    image = "ghcr.io/apache/cassandra-easy-stress:latest",
                    contactPoints = "10.0.1.6",
                    args = listOf("run", "KeyValue"),
                ),
            )

        val containers = job.spec.template.spec.containers
        assertThat(containers).hasSize(1)
        assertThat(containers[0].name).isEqualTo("stress")

        val initContainers = job.spec.template.spec.initContainers
        assertThat(initContainers).hasSize(1)
        assertThat(initContainers[0].name).isEqualTo("otel-sidecar")
        assertThat(initContainers[0].restartPolicy).isEqualTo("Always")
    }

    @Test
    fun `buildJob should configure stress container correctly`() {
        val job =
            service.buildJob(
                StressJobConfig(
                    jobName = "stress-test-123",
                    image = "ghcr.io/apache/cassandra-easy-stress:latest",
                    contactPoints = "10.0.1.6,10.0.1.7",
                    args = listOf("run", "KeyValue", "-d", "1h"),
                ),
            )

        val stress =
            job.spec.template.spec.containers
                .first { it.name == "stress" }
        assertThat(stress.image).isEqualTo("ghcr.io/apache/cassandra-easy-stress:latest")
        assertThat(stress.args).containsExactly("run", "KeyValue", "-d", "1h")

        val envMap = stress.env.associate { it.name to it.value }
        assertThat(envMap["CASSANDRA_CONTACT_POINTS"]).isEqualTo("10.0.1.6,10.0.1.7")
        assertThat(envMap["CASSANDRA_PORT"]).isEqualTo(Constants.Stress.DEFAULT_CASSANDRA_PORT.toString())
    }

    @Test
    fun `buildJob should configure otel-sidecar container with env vars and resources`() {
        val job =
            service.buildJob(
                StressJobConfig(
                    jobName = "stress-test-123",
                    image = "ghcr.io/apache/cassandra-easy-stress:latest",
                    contactPoints = "10.0.1.6",
                    args = listOf("run", "KeyValue"),
                ),
            )

        val sidecar =
            job.spec.template.spec.initContainers
                .first { it.name == "otel-sidecar" }
        assertThat(sidecar.image).isEqualTo("otel/opentelemetry-collector-contrib:0.161.0")
        assertThat(sidecar.args).containsExactly("--config=/etc/otel/otel-stress-sidecar-config.yaml")

        // Check env vars
        val envNames = sidecar.env.map { it.name }
        assertThat(envNames).containsExactly(
            "K8S_NODE_NAME",
            "HOST_IP",
            "CLUSTER_NAME",
            "GOMEMLIMIT",
            "OTEL_RESOURCE_ATTRIBUTES",
            "STRESS_PROM_PORT",
        )

        val nodeNameEnv = sidecar.env.first { it.name == "K8S_NODE_NAME" }
        assertThat(nodeNameEnv.valueFrom.fieldRef.fieldPath).isEqualTo("spec.nodeName")

        val hostIpEnv = sidecar.env.first { it.name == "HOST_IP" }
        assertThat(hostIpEnv.valueFrom.fieldRef.fieldPath).isEqualTo("status.hostIP")

        // CLUSTER_NAME is injected directly from cluster state (issue #733) so the stress job no
        // longer depends on a `cluster-config` ConfigMap that may not exist yet.
        val clusterStateManager: ClusterStateManager = getKoin().get()
        val clusterNameEnv = sidecar.env.first { it.name == "CLUSTER_NAME" }
        assertThat(clusterNameEnv.valueFrom).isNull()
        assertThat(clusterNameEnv.value).isEqualTo(clusterStateManager.load().clusterLabelName())

        val goMemLimitEnv = sidecar.env.first { it.name == "GOMEMLIMIT" }
        assertThat(goMemLimitEnv.value).isEqualTo("64MiB")

        val resourceAttrsEnv = sidecar.env.first { it.name == "OTEL_RESOURCE_ATTRIBUTES" }
        assertThat(resourceAttrsEnv.value).isEqualTo("job_name=stress-test-123")

        // No resource requests or limits on sidecar
        assertThat(sidecar.resources).isNull()
    }

    @Test
    fun `buildJob should include otel-sidecar config volume and mount`() {
        val job =
            service.buildJob(
                StressJobConfig(
                    jobName = "stress-test-123",
                    image = "ghcr.io/apache/cassandra-easy-stress:latest",
                    contactPoints = "10.0.1.6",
                    args = listOf("run", "KeyValue"),
                ),
            )

        val volumes = job.spec.template.spec.volumes
        val otelVolume = volumes.first { it.name == "otel-sidecar-config" }
        assertThat(otelVolume.configMap.name).isEqualTo("otel-stress-sidecar-config")

        val sidecar =
            job.spec.template.spec.initContainers
                .first { it.name == "otel-sidecar" }
        assertThat(sidecar.volumeMounts).hasSize(1)
        assertThat(sidecar.volumeMounts[0].name).isEqualTo("otel-sidecar-config")
        assertThat(sidecar.volumeMounts[0].mountPath).isEqualTo("/etc/otel")
        assertThat(sidecar.volumeMounts[0].readOnly).isTrue()
    }

    @Test
    fun `buildJob should use correct nodeSelector matching ServerType`() {
        val job =
            service.buildJob(
                StressJobConfig(
                    jobName = "stress-test-123",
                    image = "ghcr.io/apache/cassandra-easy-stress:latest",
                    contactPoints = "10.0.1.6",
                    args = listOf("run", "KeyValue"),
                ),
            )

        val nodeSelector = job.spec.template.spec.nodeSelector
        assertThat(nodeSelector["type"]).isEqualTo(ServerType.Stress.serverType)
        assertThat(job.spec.template.spec.hostNetwork).isTrue()
        assertThat(job.spec.template.spec.dnsPolicy).isEqualTo("ClusterFirstWithHostNet")
    }

    @Test
    fun `buildJob should set correct job metadata and spec`() {
        val job =
            service.buildJob(
                StressJobConfig(
                    jobName = "stress-test-123",
                    image = "ghcr.io/apache/cassandra-easy-stress:latest",
                    contactPoints = "10.0.1.6",
                    args = listOf("run", "KeyValue"),
                ),
            )

        assertThat(job.metadata.name).isEqualTo("stress-test-123")
        assertThat(job.metadata.namespace).isEqualTo(Constants.Stress.NAMESPACE)
        assertThat(job.metadata.labels[Constants.Stress.LABEL_KEY]).isEqualTo(Constants.Stress.LABEL_VALUE)
        assertThat(job.metadata.labels["job-name"]).isEqualTo("stress-test-123")
        assertThat(job.spec.backoffLimit).isEqualTo(0)
        assertThat(job.spec.ttlSecondsAfterFinished).isEqualTo(86400)
        assertThat(job.spec.template.spec.restartPolicy).isEqualTo("Never")
    }

    @Test
    fun `buildCommandJob should have single container and no volumes`() {
        val job =
            service.buildCommandJob(
                jobName = "cmd-test-123",
                image = "ghcr.io/apache/cassandra-easy-stress:latest",
                args = listOf("list"),
            )

        val containers = job.spec.template.spec.containers
        assertThat(containers).hasSize(1)
        assertThat(containers[0].name).isEqualTo("stress")
        assertThat(containers[0].image).isEqualTo("ghcr.io/apache/cassandra-easy-stress:latest")
        assertThat(containers[0].args).containsExactly("list")

        assertThat(job.spec.template.spec.volumes).isNullOrEmpty()
    }

    @Test
    fun `buildJob should include custom tags in OTEL_RESOURCE_ATTRIBUTES`() {
        val job =
            service.buildJob(
                StressJobConfig(
                    jobName = "stress-test-123",
                    image = "ghcr.io/apache/cassandra-easy-stress:latest",
                    contactPoints = "10.0.1.6",
                    args = listOf("run", "KeyValue"),
                    tags = mapOf("env" to "production", "team" to "platform"),
                ),
            )

        val sidecar =
            job.spec.template.spec.initContainers
                .first { it.name == "otel-sidecar" }

        val resourceAttrsEnv = sidecar.env.first { it.name == "OTEL_RESOURCE_ATTRIBUTES" }
        assertThat(resourceAttrsEnv.value).contains("job_name=stress-test-123")
        assertThat(resourceAttrsEnv.value).contains("env=production")
        assertThat(resourceAttrsEnv.value).contains("team=platform")
    }

    @Test
    fun `buildResourceAttributes should always include job_name`() {
        val result = service.buildResourceAttributes("my-job", emptyMap())
        assertThat(result).isEqualTo("job_name=my-job")
    }

    @Test
    fun `buildResourceAttributes should merge user tags with job_name`() {
        val result = service.buildResourceAttributes("my-job", mapOf("env" to "test"))
        assertThat(result).contains("job_name=my-job")
        assertThat(result).contains("env=test")
    }

    @Test
    fun `sidecar otel config resource should include the resource_detection processor`() {
        val templateService: TemplateService = getKoin().get()
        val config =
            templateService
                .fromResource(
                    DefaultStressJobService::class.java,
                    "/com/rustyrazorblade/easydblab/configuration/cassandra/otel-stress-sidecar-config.yaml",
                ).substitute()
        assertThat(config).contains("resource_detection")
        assertThat(config).contains("detectors: [env]")
        assertThat(config).contains("processors: [resource_detection, batch]")
    }

    @Test
    fun `buildJob should set CASSANDRA_EASY_STRESS_PROM_PORT on stress container`() {
        val job =
            service.buildJob(
                StressJobConfig(
                    jobName = "keyvalue-1",
                    image = "ghcr.io/apache/cassandra-easy-stress:latest",
                    contactPoints = "10.0.1.6",
                    args = listOf("run", "KeyValue"),
                    promPort = 9501,
                ),
            )

        val stress =
            job.spec.template.spec.containers
                .first { it.name == "stress" }
        val envMap = stress.env.associate { it.name to it.value }
        assertThat(envMap["CASSANDRA_EASY_STRESS_PROM_PORT"]).isEqualTo("9501")
    }

    @Test
    fun `buildJob should set STRESS_PROM_PORT on otel-sidecar container`() {
        val job =
            service.buildJob(
                StressJobConfig(
                    jobName = "keyvalue-1",
                    image = "ghcr.io/apache/cassandra-easy-stress:latest",
                    contactPoints = "10.0.1.6",
                    args = listOf("run", "KeyValue"),
                    promPort = 9502,
                ),
            )

        val sidecar =
            job.spec.template.spec.initContainers
                .first { it.name == "otel-sidecar" }
        val envMap = sidecar.env.associate { it.name to (it.value ?: "") }
        assertThat(envMap["STRESS_PROM_PORT"]).isEqualTo("9502")
    }

    @Test
    fun `buildCommandJob should use correct nodeSelector and shorter TTL`() {
        val job =
            service.buildCommandJob(
                jobName = "cmd-test-123",
                image = "ghcr.io/apache/cassandra-easy-stress:latest",
                args = listOf("list"),
            )

        assertThat(job.spec.template.spec.nodeSelector["type"]).isEqualTo(ServerType.Stress.serverType)
        assertThat(job.spec.template.spec.hostNetwork).isTrue()
        assertThat(job.spec.template.spec.dnsPolicy).isEqualTo("ClusterFirstWithHostNet")
        assertThat(job.spec.ttlSecondsAfterFinished).isEqualTo(300)
        assertThat(job.spec.backoffLimit).isEqualTo(0)
    }

    @Test
    fun `buildJob should include pyroscope hostPath volume`() {
        val job =
            service.buildJob(
                StressJobConfig(
                    jobName = "stress-test-123",
                    image = "ghcr.io/apache/cassandra-easy-stress:latest",
                    contactPoints = "10.0.1.6",
                    args = listOf("run", "KeyValue"),
                ),
            )

        val volumes = job.spec.template.spec.volumes
        val pyroscopeVolume = volumes.first { it.name == "pyroscope-agent" }
        assertThat(pyroscopeVolume.hostPath.path).isEqualTo("/usr/local/pyroscope")
        assertThat(pyroscopeVolume.hostPath.type).isEqualTo("Directory")
    }

    @Test
    fun `buildJob should mount pyroscope volume in stress container as read-only`() {
        val job =
            service.buildJob(
                StressJobConfig(
                    jobName = "stress-test-123",
                    image = "ghcr.io/apache/cassandra-easy-stress:latest",
                    contactPoints = "10.0.1.6",
                    args = listOf("run", "KeyValue"),
                ),
            )

        val stress =
            job.spec.template.spec.containers
                .first { it.name == "stress" }
        val pyroscopeMount = stress.volumeMounts.first { it.name == "pyroscope-agent" }
        assertThat(pyroscopeMount.mountPath).isEqualTo("/usr/local/pyroscope")
        assertThat(pyroscopeMount.readOnly).isTrue()
    }

    @Test
    fun `buildJob should set JAVA_TOOL_OPTIONS with pyroscope agent config`() {
        val job =
            service.buildJob(
                StressJobConfig(
                    jobName = "stress-test-123",
                    image = "ghcr.io/apache/cassandra-easy-stress:latest",
                    contactPoints = "10.0.1.6",
                    args = listOf("run", "KeyValue"),
                ),
            )

        val stress =
            job.spec.template.spec.containers
                .first { it.name == "stress" }
        val javaToolOptions = stress.env.first { it.name == "JAVA_TOOL_OPTIONS" }.value

        assertThat(javaToolOptions).contains("-javaagent:/usr/local/pyroscope/pyroscope.jar")
        assertThat(javaToolOptions).contains("-Dpyroscope.application.name=cassandra-easy-stress")
        assertThat(javaToolOptions).contains("-Dpyroscope.server.address=http://10.0.1.5:${Constants.K8s.PYROSCOPE_PORT}")
        assertThat(javaToolOptions).contains("-Dpyroscope.format=jfr")
        assertThat(javaToolOptions).contains("-Dpyroscope.profiler.event=cpu")
        assertThat(javaToolOptions).contains("-Dpyroscope.profiler.alloc=512k")
        assertThat(javaToolOptions).contains("-Dpyroscope.profiler.lock=10ms")
        assertThat(javaToolOptions).contains("-Dpyroscope.labels=cluster=test-cluster,job_name=stress-test-123")
        assertThat(javaToolOptions).contains("-Dpyroscope.tenant.id=default")
    }

    /**
     * The stress JVM is the only source of client spans on the whole cluster.
     *
     * Cassandra has no OTel server-side instrumentation, so nothing on the server side can emit a
     * trace. If these flags are wrong, Tempo receives nothing, spanmetrics generates nothing, and
     * every RED panel is empty — with no error anywhere to say why.
     */
    @Test
    fun `buildJob should load the OTel agent alongside Pyroscope`() {
        val javaToolOptions = javaToolOptionsOf(stressJobConfig())

        assertThat(javaToolOptions).contains("-javaagent:/usr/local/otel/opentelemetry-javaagent.jar")
        assertThat(javaToolOptions).contains("-Dotel.service.name=cassandra-easy-stress")
        // Two agents in one JVM: adding OTel must not cost the profiling already there.
        assertThat(javaToolOptions).contains("-javaagent:/usr/local/pyroscope/pyroscope.jar")
    }

    @Test
    fun `buildJob should export OTLP to the collector's HTTP port, not its gRPC port`() {
        // The agent's default protocol is http/protobuf, served on 4318. Sent at 4317 — the gRPC
        // port — every export fails with "HttpExporter - Failed to export" and no span arrives.
        val javaToolOptions = javaToolOptionsOf(stressJobConfig())

        assertThat(javaToolOptions).contains("-Dotel.exporter.otlp.endpoint=http://10.0.1.5:${Constants.K8s.OTEL_HTTP_PORT}")
        assertThat(javaToolOptions).doesNotContain("4317")
    }

    @Test
    fun `buildJob should give spans the same identity as the sidecar's metrics`() {
        // Same helper as the sidecar's OTEL_RESOURCE_ATTRIBUTES, so a trace and the stress metrics
        // from one run line up on job_name and tags.
        // Just the agent's attribute list. Pyroscope's own -Dpyroscope.labels sits in the same
        // string and legitimately carries cluster=, so asserting over the whole thing would pass
        // for the wrong reason.
        val resourceAttributes =
            javaToolOptionsOf(stressJobConfig(tags = mapOf("variant" to "baseline")))
                .substringAfter("-Dotel.resource.attributes=")
                .substringBefore(" ")

        assertThat(resourceAttributes).contains("job_name=stress-test-123")
        assertThat(resourceAttributes).contains("variant=baseline")
        // The cluster label is NOT set here. The collector's traces pipeline stamps it with
        // resource/cluster, so every span producer gets it rather than each carrying its own copy.
        assertThat(resourceAttributes).doesNotContain("cluster=")
    }

    @Test
    fun `buildJob should not export logs twice, and should keep metrics on`() {
        val javaToolOptions = javaToolOptionsOf(stressJobConfig())

        // The pod's stdout already reaches VictoriaLogs via the collector's filelog receiver.
        assertThat(javaToolOptions).contains("-Dotel.logs.exporter=none")
        // The sidecar scrapes only cassandra-easy-stress's own counters, so the agent's JVM metrics
        // are the only view of whether the load generator itself is the bottleneck.
        assertThat(javaToolOptions).doesNotContain("-Dotel.metrics.exporter=none")
    }

    @Test
    fun `buildJob should mount the OTel agent from the host, read-only`() {
        // The jar is installed by the base AMI at /usr/local/otel and reaches the pod exactly as
        // the Pyroscope jar does. The job's nodeSelector pins it to a type=app node, where that
        // path exists.
        val spec =
            service
                .buildJob(stressJobConfig())
                .spec.template.spec
        val mount =
            spec.containers
                .first { it.name == "stress" }
                .volumeMounts
                .first { it.name == "otel-agent" }
        val volume = spec.volumes.first { it.name == "otel-agent" }

        assertThat(mount.mountPath).isEqualTo("/usr/local/otel")
        assertThat(mount.readOnly).isTrue()
        assertThat(volume.hostPath.path).isEqualTo("/usr/local/otel")
        assertThat(volume.hostPath.type).isEqualTo("Directory")
    }

    @Test
    fun `buildJob points the Pyroscope agent at the external stack on a redirect cluster`() {
        // On a redirect cluster the control node runs no Pyroscope server. The stress job's agent
        // must ship to the external stack, not to a control-node address with nothing listening.
        val redirect = TelemetryRedirect.fromBaseHost("10.9.9.9")
        val clusterStateManager: ClusterStateManager = getKoin().get()
        whenever(clusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                infrastructure = InfrastructureState(vpcId = "vpc-test", region = "us-west-2"),
                hosts =
                    mapOf(
                        ServerType.Control to
                            listOf(
                                ClusterHost(
                                    publicIp = "54.123.45.67",
                                    privateIp = "10.0.1.5",
                                    alias = "control0",
                                    availabilityZone = "us-west-2a",
                                    instanceId = "i-control123",
                                ),
                            ),
                    ),
                initConfig = InitConfig(telemetryRedirect = redirect, tenant = "acme"),
            ),
        )

        val javaToolOptions = javaToolOptionsOf(stressJobConfig())

        assertThat(javaToolOptions).contains("-Dpyroscope.server.address=${redirect.profiles}")
        assertThat(javaToolOptions).contains("-Dpyroscope.tenant.id=acme")
        assertThat(javaToolOptions)
            .describedAs("a redirect cluster must not point the stress agent at the control node")
            .doesNotContain("http://10.0.1.5:${Constants.K8s.PYROSCOPE_PORT}")
    }

    private fun stressJobConfig(tags: Map<String, String> = emptyMap()) =
        StressJobConfig(
            jobName = "stress-test-123",
            image = "ghcr.io/apache/cassandra-easy-stress:latest",
            contactPoints = "10.0.1.6",
            args = listOf("run", "KeyValue"),
            tags = tags,
        )

    private fun javaToolOptionsOf(config: StressJobConfig): String =
        service
            .buildJob(config)
            .spec.template.spec.containers
            .first { it.name == "stress" }
            .env
            .first { it.name == "JAVA_TOOL_OPTIONS" }
            .value
}

/**
 * Characterizes how `startJob` waits for the job's pod: it polls the job's pods until the first
 * one is Running or Succeeded, retrying every other outcome — no pod yet, a pod in another phase,
 * a Failed pod, a failed query — up to the attempt budget, and fails with the last outcome's
 * message. Runs with a zero poll interval.
 */
class DefaultStressJobServicePodWaitTest : BaseKoinTest() {
    private val k8sService: K8sService = mock()
    private val events = mutableListOf<Event>()

    private val controlHost =
        ClusterHost(publicIp = "54.1.2.3", privateIp = "10.0.1.5", alias = "control0", availabilityZone = "us-west-2a")

    private val config =
        StressJobConfig(
            jobName = "stress-wait",
            image = "ghcr.io/apache/cassandra-easy-stress:latest",
            contactPoints = "10.0.1.6",
            args = listOf("run", "KeyValue"),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(
                            ClusterState(
                                name = "test-cluster",
                                versions = mutableMapOf(),
                                infrastructure = InfrastructureState(vpcId = "vpc-test", region = "us-west-2"),
                                hosts = mapOf(ServerType.Control to listOf(controlHost)),
                            ),
                        )
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    private fun service(): DefaultStressJobService {
        val eventBus =
            EventBus().also { bus ->
                bus.addListener(
                    object : EventListener {
                        override fun onEvent(envelope: EventEnvelope) {
                            events.add(envelope.event)
                        }

                        override fun close() = Unit
                    },
                )
            }
        val ecrPullSecrets: EcrPullSecretService = mock()
        whenever(ecrPullSecrets.ensureFor(any(), any(), any())).thenReturn("")
        return DefaultStressJobService(
            k8sService = k8sService,
            clusterStateManager = getKoin().get(),
            eventBus = eventBus,
            templateService = getKoin().get(),
            ecrPullSecrets = ecrPullSecrets,
            podReadyPollInterval = Duration.ZERO,
        )
    }

    private fun pod(phase: String) =
        KubernetesPod(namespace = "stress", name = "stress-wait-abc", status = phase, ready = "0/1", restarts = 0, age = Duration.ZERO)

    @BeforeEach
    fun stubJobCreation() {
        whenever(k8sService.createConfigMap(any(), any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(k8sService.createJob(any(), any(), any<Job>())).thenReturn(Result.success("stress-wait"))
    }

    @Test
    fun `returns the job name and reports the pod once it is Running`() {
        whenever(k8sService.getPodsForJob(any(), any(), any())).thenReturn(
            Result.success(emptyList()),
            Result.failure(IllegalStateException("connection reset")),
            Result.success(listOf(pod("Pending"))),
            Result.success(listOf(pod("Running"))),
        )

        assertThat(service().startJob(controlHost, config).getOrThrow()).isEqualTo("stress-wait")
        assertThat(events.filterIsInstance<Event.Stress.PodStatus>())
            .containsExactly(Event.Stress.PodStatus("stress-wait-abc", "Running"))
        verify(k8sService, times(4)).getPodsForJob(any(), any(), any())
    }

    @Test
    fun `a Succeeded pod also ends the wait`() {
        whenever(k8sService.getPodsForJob(any(), any(), any())).thenReturn(Result.success(listOf(pod("Succeeded"))))

        assertThat(service().startJob(controlHost, config).getOrThrow()).isEqualTo("stress-wait")
    }

    @Test
    fun `fails naming the job when no pod is ever created`() {
        whenever(k8sService.getPodsForJob(any(), any(), any())).thenReturn(Result.success(emptyList()))

        val result = service().startJob(controlHost, config)

        assertThat(result.exceptionOrNull()).hasMessage("No pods created yet for job stress-wait")
        verify(k8sService, times(POD_READY_MAX_ATTEMPTS)).getPodsForJob(any(), any(), any())
    }

    @Test
    fun `fails naming the pod's phase when it never runs`() {
        whenever(k8sService.getPodsForJob(any(), any(), any())).thenReturn(Result.success(listOf(pod("Pending"))))

        val result = service().startJob(controlHost, config)

        assertThat(result.exceptionOrNull()).hasMessage("Pod stress-wait-abc is Pending, waiting for Running")
        verify(k8sService, times(POD_READY_MAX_ATTEMPTS)).getPodsForJob(any(), any(), any())
    }

    @Test
    fun `a Failed pod is polled again, and fails the wait when it stays Failed`() {
        whenever(k8sService.getPodsForJob(any(), any(), any())).thenReturn(Result.success(listOf(pod("Failed"))))

        val result = service().startJob(controlHost, config)

        assertThat(result.exceptionOrNull()).hasMessage("Pod stress-wait-abc failed")
        verify(k8sService, times(POD_READY_MAX_ATTEMPTS)).getPodsForJob(any(), any(), any())
    }

    private companion object {
        const val POD_READY_MAX_ATTEMPTS = 10
    }
}
