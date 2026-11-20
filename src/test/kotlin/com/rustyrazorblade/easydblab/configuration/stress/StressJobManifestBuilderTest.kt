package com.rustyrazorblade.easydblab.configuration.stress

import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.services.StressJobConfig
import com.rustyrazorblade.easydblab.services.stress.StressTarget
import io.fabric8.kubernetes.client.utils.Serialization
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [StressJobManifestBuilder] is pure: every test here builds a Job from a [StressJobConfig] and a
 * [StressCluster] and needs no state file, no Koin and no cluster.
 *
 * The golden files under `services/stress-golden/` pin the Jobs the cluster receives for these
 * inputs, so these tests compare the rendered YAML with them.
 */
class StressJobManifestBuilderTest {
    private val config =
        StressJobConfig(
            jobName = "stress-golden",
            image = "ghcr.io/apache/cassandra-easy-stress:latest",
            args = listOf("run", "KeyValue", "-d", "1h"),
            target = StressTarget("10.0.1.6,10.0.1.7", 9042, "us-west-2"),
            tags = mapOf("team" to "perf", "run" to "42"),
            promPort = 9500,
        )

    private val cluster =
        StressCluster(
            clusterName = "test-cluster-golden-cluster-id",
            tenant = "acme",
            telemetryRedirect = null,
            appSelector = RoleSelectors.OWNED_NODES.app,
        )

    private fun golden(name: String): String =
        requireNotNull(javaClass.getResource("/com/rustyrazorblade/easydblab/services/stress-golden/$name")) { "missing golden $name" }
            .readText()

    @Test
    fun `the stress Job renders exactly as the pre-split service rendered it`() {
        assertThat(Serialization.asYaml(StressJobManifestBuilder.buildStressJob(config, cluster)))
            .isEqualTo(golden("stress-job-local.yaml"))
    }

    @Test
    fun `a telemetry redirect sends the profiles to its Pyroscope and nothing else changes`() {
        val redirected = cluster.copy(telemetryRedirect = TelemetryRedirect(profiles = "http://obs.example.com:4040"))

        assertThat(Serialization.asYaml(StressJobManifestBuilder.buildStressJob(config, redirected)))
            .isEqualTo(golden("stress-job-redirect.yaml"))
    }

    @Test
    fun `the command Job renders exactly as the pre-split service rendered it`() {
        val job =
            StressJobManifestBuilder.buildCommandJob(
                "cmd-1700000000",
                "ghcr.io/apache/cassandra-easy-stress:latest",
                listOf("list"),
                RoleSelectors.OWNED_NODES.app,
            )

        assertThat(Serialization.asYaml(job)).isEqualTo(golden("command-job.yaml"))
    }

    @Test
    fun `the Job mounts the sidecar ConfigMap that StressSidecarConfig creates and runs the config file it holds`() {
        val job = StressJobManifestBuilder.buildStressJob(config, cluster)
        val podSpec = job.spec.template.spec

        assertThat(
            podSpec.volumes
                .single { it.name == "otel-sidecar-config" }
                .configMap.name,
        ).isEqualTo(StressSidecarConfig.CONFIG_MAP_NAME)
        assertThat(podSpec.initContainers.single { it.name == "otel-sidecar" }.args)
            .containsExactly("--config=/etc/otel/${StressSidecarConfig.FILE_NAME}")
    }

    @Test
    fun `resource attributes always lead with the job name, then the tags in order`() {
        assertThat(StressJobManifestBuilder.buildResourceAttributes("j1", linkedMapOf("b" to "2", "a" to "1")))
            .isEqualTo("job_name=j1,b=2,a=1")
    }
}
