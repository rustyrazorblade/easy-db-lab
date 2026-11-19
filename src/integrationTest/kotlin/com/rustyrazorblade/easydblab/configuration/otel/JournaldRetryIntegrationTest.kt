package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import java.time.Duration
import java.util.UUID

/**
 * Runs the pinned Fluent Bit image with the journald configuration `up` renders, and proves that a
 * chunk survives a long node collector outage (issue #991). Fluent Bit's defaults retry a chunk once,
 * and back off up to 2000 seconds, so a collector that came back after a few minutes waited many
 * minutes for its next try, or got nothing.
 *
 * Fluent Bit sends to `127.0.0.1:4318`, as on a node. No collector listens there until Fluent Bit
 * has failed [RETRIES_BEFORE_COLLECTOR] tries in a row. Then a collector starts in its network
 * namespace, as the node collector does after a roll, and the test asserts that the chunk arrives
 * within [ARRIVAL], which a backoff past the 30-second cap would miss, and that no retry was put off
 * longer than the cap.
 */
class JournaldRetryIntegrationTest : BaseKoinTest() {
    private companion object {
        const val RETRIES_BEFORE_COLLECTOR = 5
        const val SCHEDULER_CAP_SECONDS = 30
        val FAILING: Duration = Duration.ofMinutes(5)
        val ARRIVAL: Duration = Duration.ofSeconds(60)
        val POLL: Duration = Duration.ofSeconds(2)
        val RETRY_IN = Regex("retry in (\\d+) seconds")
    }

    private val probe = "journald-retry-${UUID.randomUUID()}"
    private val containers = mutableListOf<GenericContainer<*>>()
    private val state = ClusterState(name = "journald-retry", initConfig = InitConfig(tenant = "acme"))

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager>().also { whenever(it.load()).thenReturn(state) } }
                single { TemplateService(get(), get()) }
            },
        )

    @AfterEach
    fun cleanUp() {
        containers.reversed().forEach { it.stop() }
    }

    /**
     * Fluent Bit with the rendered configuration and its Lua filter. The journal directory is empty,
     * so the probe comes from one extra `dummy` input tagged as the journal, which takes the same
     * filters and output.
     */
    private fun startFluentBit(): GenericContainer<*> {
        val data = JournaldOtelManifestBuilder(getKoin().get()).buildConfigMap().data
        return GenericContainer(JournaldOtelManifestBuilder.IMAGE)
            .withCopyToContainer(
                Transferable.of(data.getValue(JournaldOtelManifestBuilder.CONFIG_DATA_KEY)),
                "/fluent-bit/etc/config.yaml",
            ).withCopyToContainer(
                Transferable.of(data.getValue(JournaldOtelManifestBuilder.SEVERITY_MAPPER_KEY)),
                "/fluent-bit/etc/severity-mapper.lua",
            ).withTmpFs(mapOf("/var/log/journal" to "rw"))
            .withCommand(
                "/fluent-bit/bin/fluent-bit",
                "--config=/fluent-bit/etc/config.yaml",
                "-i",
                "dummy",
                "-t",
                "journald",
                "-p",
                "samples=1",
                "-p",
                """dummy={"MESSAGE":"$probe","PRIORITY":"6"}""",
            ).waitingFor(Wait.forLogMessage(".*${RETRY_IN.pattern}.*", RETRIES_BEFORE_COLLECTOR).withStartupTimeout(FAILING))
            .also {
                containers.add(it)
                it.start()
            }
    }

    private fun startCollector(fluentBit: GenericContainer<*>): GenericContainer<*> {
        val config =
            checkNotNull(javaClass.getResource("/com/rustyrazorblade/easydblab/configuration/otel/otlp-debug-collector.yaml"))
                .readText()
        return GenericContainer("otel/opentelemetry-collector-contrib:${Constants.OtelCollector.VERSION}")
            .withCopyToContainer(Transferable.of(config), "/etc/otel-collector-config.yaml")
            .withCommand("--config=/etc/otel-collector-config.yaml")
            .withNetworkMode("container:${fluentBit.containerId}")
            .waitingFor(Wait.forLogMessage(".*Everything is ready.*", 1))
            .also {
                containers.add(it)
                it.start()
            }
    }

    @Test
    fun `a journal chunk is retried without a limit and arrives soon after the collector is back`() {
        val fluentBit = startFluentBit()
        val collector = startCollector(fluentBit)

        val deadline = System.nanoTime() + ARRIVAL.toNanos()
        while (probe !in collector.logs && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
        }

        assertThat(collector.logs)
            .describedAs("the collector received the journal chunk; Fluent Bit log:\n${fluentBit.logs}")
            .contains(probe)
        assertThat(RETRY_IN.findAll(fluentBit.logs).map { it.groupValues[1].toInt() }.toList())
            .describedAs("seconds Fluent Bit put each retry off")
            .hasSizeGreaterThanOrEqualTo(RETRIES_BEFORE_COLLECTOR)
            .allMatch { it <= SCHEDULER_CAP_SECONDS }
    }
}
