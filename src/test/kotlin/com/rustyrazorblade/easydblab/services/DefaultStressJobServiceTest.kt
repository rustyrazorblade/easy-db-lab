package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock

/**
 * Tests for DefaultStressJobService Job building.
 *
 * Verifies that buildJob includes the OTel sidecar container for metrics collection,
 * and that buildCommandJob (short-lived commands) does not include it.
 */
class DefaultStressJobServiceTest : BaseKoinTest() {
    private lateinit var service: DefaultStressJobService
    private lateinit var mockK8sService: K8sService

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<K8sService>().also { mockK8sService = it } }
            },
        )

    @BeforeEach
    fun setup() {
        mockK8sService = getKoin().get()
        val outputHandler: OutputHandler = getKoin().get()
        service = DefaultStressJobService(mockK8sService, outputHandler)
    }

    @Test
    fun `buildJob should include stress and otel-sidecar containers`() {
        val job =
            service.buildJob(
                jobName = "stress-test-123",
                image = "ghcr.io/apache/cassandra-easy-stress:latest",
                contactPoints = "10.0.1.6",
                args = listOf("run", "KeyValue"),
            )

        val containers = job.spec.template.spec.containers
        assertThat(containers).hasSize(2)
        assertThat(containers.map { it.name }).containsExactly("stress", "otel-sidecar")
    }

    @Test
    fun `buildJob should configure stress container correctly`() {
        val job =
            service.buildJob(
                jobName = "stress-test-123",
                image = "ghcr.io/apache/cassandra-easy-stress:latest",
                contactPoints = "10.0.1.6,10.0.1.7",
                args = listOf("run", "KeyValue", "-d", "1h"),
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
                jobName = "stress-test-123",
                image = "ghcr.io/apache/cassandra-easy-stress:latest",
                contactPoints = "10.0.1.6",
                args = listOf("run", "KeyValue"),
            )

        val sidecar =
            job.spec.template.spec.containers
                .first { it.name == "otel-sidecar" }
        assertThat(sidecar.image).isEqualTo("otel/opentelemetry-collector-contrib:latest")
        assertThat(sidecar.args).containsExactly("--config=/etc/otel/otel-stress-sidecar-config.yaml")

        // Check env vars
        val envNames = sidecar.env.map { it.name }
        assertThat(envNames).containsExactly(
            "K8S_NODE_NAME",
            "HOST_IP",
            "CLUSTER_NAME",
            "GOMEMLIMIT",
            "OTEL_RESOURCE_ATTRIBUTES",
        )

        val nodeNameEnv = sidecar.env.first { it.name == "K8S_NODE_NAME" }
        assertThat(nodeNameEnv.valueFrom.fieldRef.fieldPath).isEqualTo("spec.nodeName")

        val hostIpEnv = sidecar.env.first { it.name == "HOST_IP" }
        assertThat(hostIpEnv.valueFrom.fieldRef.fieldPath).isEqualTo("status.hostIP")

        val clusterNameEnv = sidecar.env.first { it.name == "CLUSTER_NAME" }
        assertThat(clusterNameEnv.valueFrom.configMapKeyRef.name).isEqualTo("cluster-config")
        assertThat(clusterNameEnv.valueFrom.configMapKeyRef.key).isEqualTo("cluster_name")

        val goMemLimitEnv = sidecar.env.first { it.name == "GOMEMLIMIT" }
        assertThat(goMemLimitEnv.value).isEqualTo("64MiB")

        val resourceAttrsEnv = sidecar.env.first { it.name == "OTEL_RESOURCE_ATTRIBUTES" }
        assertThat(resourceAttrsEnv.value).isEqualTo("job_name=stress-test-123")

        // Check resources (requests only, no limits)
        assertThat(sidecar.resources.requests["memory"].toString()).isEqualTo("32Mi")
        assertThat(sidecar.resources.requests["cpu"].toString()).isEqualTo("25m")
        assertThat(sidecar.resources.limits).isNullOrEmpty()
    }

    @Test
    fun `buildJob should include otel-sidecar config volume and mount`() {
        val job =
            service.buildJob(
                jobName = "stress-test-123",
                image = "ghcr.io/apache/cassandra-easy-stress:latest",
                contactPoints = "10.0.1.6",
                args = listOf("run", "KeyValue"),
            )

        val volumes = job.spec.template.spec.volumes
        assertThat(volumes).hasSize(1)
        assertThat(volumes[0].name).isEqualTo("otel-sidecar-config")
        assertThat(volumes[0].configMap.name).isEqualTo("otel-stress-sidecar-config")

        val sidecar =
            job.spec.template.spec.containers
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
                jobName = "stress-test-123",
                image = "ghcr.io/apache/cassandra-easy-stress:latest",
                contactPoints = "10.0.1.6",
                args = listOf("run", "KeyValue"),
            )

        val nodeSelector = job.spec.template.spec.nodeSelector
        assertThat(nodeSelector["type"]).isEqualTo(ServerType.Stress.serverType)
    }

    @Test
    fun `buildJob should set correct job metadata and spec`() {
        val job =
            service.buildJob(
                jobName = "stress-test-123",
                image = "ghcr.io/apache/cassandra-easy-stress:latest",
                contactPoints = "10.0.1.6",
                args = listOf("run", "KeyValue"),
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
                jobName = "stress-test-123",
                image = "ghcr.io/apache/cassandra-easy-stress:latest",
                contactPoints = "10.0.1.6",
                args = listOf("run", "KeyValue"),
                tags = mapOf("env" to "production", "team" to "platform"),
            )

        val sidecar =
            job.spec.template.spec.containers
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
    fun `SIDECAR_OTEL_CONFIG should include resourcedetection processor`() {
        assertThat(DefaultStressJobService.SIDECAR_OTEL_CONFIG).contains("resourcedetection")
        assertThat(DefaultStressJobService.SIDECAR_OTEL_CONFIG).contains("detectors: [env]")
        assertThat(DefaultStressJobService.SIDECAR_OTEL_CONFIG).contains("processors: [resourcedetection, batch]")
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
        assertThat(job.spec.ttlSecondsAfterFinished).isEqualTo(300)
        assertThat(job.spec.backoffLimit).isEqualTo(0)
    }
}
