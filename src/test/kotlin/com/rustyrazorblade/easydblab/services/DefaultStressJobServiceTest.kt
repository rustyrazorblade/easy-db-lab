package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock

/**
 * Tests for DefaultStressJobService YAML generation.
 *
 * Verifies that buildJobYaml includes the OTel sidecar container for metrics collection,
 * and that buildCommandJobYaml (short-lived commands) does not include it.
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
    fun `buildJobYaml should include otel-sidecar container`() {
        val yaml =
            service.buildJobYaml(
                jobName = "stress-test-123",
                image = "ghcr.io/apache/cassandra-easy-stress:latest",
                contactPoints = "10.0.1.6",
                args = listOf("run", "KeyValue"),
                profileConfigMapName = null,
            )

        assertThat(yaml).contains("name: otel-sidecar")
        assertThat(yaml).contains("image: otel/opentelemetry-collector-contrib:latest")
        assertThat(yaml).contains("--config=/etc/otel/otel-stress-sidecar-config.yaml")
    }

    @Test
    fun `buildJobYaml should include otel-sidecar env vars`() {
        val yaml =
            service.buildJobYaml(
                jobName = "stress-test-123",
                image = "ghcr.io/apache/cassandra-easy-stress:latest",
                contactPoints = "10.0.1.6",
                args = listOf("run", "KeyValue"),
                profileConfigMapName = null,
            )

        assertThat(yaml).contains("name: K8S_NODE_NAME")
        assertThat(yaml).contains("fieldPath: spec.nodeName")
        assertThat(yaml).contains("name: HOST_IP")
        assertThat(yaml).contains("fieldPath: status.hostIP")
        assertThat(yaml).contains("name: CLUSTER_NAME")
        assertThat(yaml).contains("key: cluster_name")
        assertThat(yaml).contains("name: GOMEMLIMIT")
        assertThat(yaml).contains("value: \"64MiB\"")
    }

    @Test
    fun `buildJobYaml should include otel-sidecar config volume`() {
        val yaml =
            service.buildJobYaml(
                jobName = "stress-test-123",
                image = "ghcr.io/apache/cassandra-easy-stress:latest",
                contactPoints = "10.0.1.6",
                args = listOf("run", "KeyValue"),
                profileConfigMapName = null,
            )

        assertThat(yaml).contains("name: otel-sidecar-config")
        assertThat(yaml).contains("mountPath: /etc/otel")
        assertThat(yaml).contains("name: otel-stress-sidecar-config")
    }

    @Test
    fun `buildJobYaml should include otel-sidecar resource limits`() {
        val yaml =
            service.buildJobYaml(
                jobName = "stress-test-123",
                image = "ghcr.io/apache/cassandra-easy-stress:latest",
                contactPoints = "10.0.1.6",
                args = listOf("run", "KeyValue"),
                profileConfigMapName = null,
            )

        assertThat(yaml).contains("memory: \"32Mi\"")
        assertThat(yaml).contains("cpu: \"25m\"")
        assertThat(yaml).contains("memory: \"64Mi\"")
    }

    @Test
    fun `buildJobYaml with profile should include both profile and otel volumes`() {
        val yaml =
            service.buildJobYaml(
                jobName = "stress-test-123",
                image = "ghcr.io/apache/cassandra-easy-stress:latest",
                contactPoints = "10.0.1.6",
                args = listOf("run", "KeyValue"),
                profileConfigMapName = "stress-test-123-profile",
            )

        // Should have otel sidecar
        assertThat(yaml).contains("name: otel-sidecar")
        // Should have profile volume mount on stress container
        assertThat(yaml).contains("name: profiles")
        assertThat(yaml).contains("mountPath: /profiles")
        // Should have both volumes
        assertThat(yaml).contains("name: otel-sidecar-config")
        assertThat(yaml).contains("name: stress-test-123-profile")
    }

    @Test
    fun `buildJobYaml should still contain stress container`() {
        val yaml =
            service.buildJobYaml(
                jobName = "stress-test-123",
                image = "ghcr.io/apache/cassandra-easy-stress:latest",
                contactPoints = "10.0.1.6,10.0.1.7",
                args = listOf("run", "KeyValue", "-d", "1h"),
                profileConfigMapName = null,
            )

        assertThat(yaml).contains("name: stress")
        assertThat(yaml).contains("image: ghcr.io/apache/cassandra-easy-stress:latest")
        assertThat(yaml).contains("CASSANDRA_CONTACT_POINTS")
        assertThat(yaml).contains("10.0.1.6,10.0.1.7")
    }
}
