package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.images.builder.Transferable
import java.time.Duration

/**
 * Runs `otelcol-contrib validate` from the pinned collector image against every collector
 * configuration this tool renders. The collector rejects unknown keys and unknown component types,
 * so a key removed or renamed by an upgrade fails here instead of on a cluster — where the DaemonSet
 * would crash-loop and take every signal with it.
 */
class OtelCollectorConfigValidationIntegrationTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(
                            ClusterState(name = "test", versions = mutableMapOf(), initConfig = InitConfig(tenant = "acme")),
                        )
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    private fun rendered(variant: String): String {
        val builder = OtelManifestBuilder(getKoin().get())
        val kitJob =
            WorkloadScrapeConfig(
                kitName = "postgres",
                jobName = "postgres",
                port = 30187,
                path = "/metrics",
                username = "",
                podSelector = "",
            )
        val podJob =
            WorkloadScrapeConfig(
                kitName = "tidb",
                jobName = "tikv",
                port = 20180,
                path = "/metrics",
                username = "",
                podSelector = "app.kubernetes.io/component=tikv",
            )
        val clusterConfig = { configMap: io.fabric8.kubernetes.api.model.ConfigMap ->
            configMap.data.getValue("otel-collector-config.yaml")
        }
        return when (variant) {
            "cluster-flannel" -> clusterConfig(builder.buildConfigMap(emptyList(), null, CniMode.Flannel))
            "cluster-cilium-with-kits" -> clusterConfig(builder.buildConfigMap(listOf(kitJob, podJob), null, CniMode.Cilium))
            "cluster-redirect" ->
                clusterConfig(
                    builder.buildConfigMap(emptyList(), TelemetryRedirect.fromBaseHost("10.0.0.9"), CniMode.Cilium),
                )
            "emr" ->
                checkNotNull(javaClass.getResource("/com/rustyrazorblade/easydblab/configuration/emr/otel-collector-config.yaml"))
                    .readText()
                    .replace("__CONTROL_NODE_IP__", "10.0.0.1")
                    .replace("__NODE_ROLE__", "spark-master")
            "stress-sidecar" ->
                checkNotNull(
                    javaClass.getResource("/com/rustyrazorblade/easydblab/configuration/cassandra/otel-stress-sidecar-config.yaml"),
                ).readText()
            else -> error("unknown variant $variant")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["cluster-flannel", "cluster-cilium-with-kits", "cluster-redirect", "emr", "stress-sidecar"])
    fun `the pinned collector accepts the rendered configuration`(variant: String) {
        val collector =
            GenericContainer("otel/opentelemetry-collector-contrib:${Constants.OtelCollector.VERSION}")
                .withCopyToContainer(Transferable.of(rendered(variant)), "/cfg/config.yaml")
                // host_metrics checks that its root_path exists.
                .withCopyToContainer(Transferable.of(""), "${OtelManifestBuilder.HOST_ROOT_MOUNT_PATH}/.keep")
                .withEnv("HOSTNAME", "node0")
                .withEnv("CLUSTER_NAME", "test-cluster")
                .withEnv("TENANT", "acme")
                .withEnv("STRESS_PROM_PORT", "9500")
                .withEnv("K8S_NODE_NAME", "node0")
                .withEnv("HOST_IP", "10.0.0.1")
                .withCommand("validate", "--config=/cfg/config.yaml")
                .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))

        try {
            collector.start()
        } catch (e: Exception) {
            throw AssertionError("collector rejected the $variant configuration:\n${collector.logs}", e)
        }

        assertThat(collector.currentContainerInfo.state.exitCodeLong).isZero()
        collector.stop()
    }
}
