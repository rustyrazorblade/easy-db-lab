package com.rustyrazorblade.jvmpauseagent

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Background daemon thread that measures JVM pauses by tracking sleep overshoot.
 *
 * The algorithm: sleep for [pollMs] milliseconds, then measure how much longer the sleep
 * actually took. The overshoot is the time the JVM was completely paused (GC stop-the-world,
 * safepoint stalls, OS scheduling jitter).
 *
 * Configuration via JVM system properties:
 * - `jvm.pause.agent.otlp.endpoint` — OTLP gRPC endpoint (default: http://localhost:4317)
 * - `jvm.pause.agent.poll.ms` — poll interval in ms (default: 100)
 * - `jvm.pause.agent.cluster.name` — cluster name label (default: unknown)
 * - `jvm.pause.agent.service.name` — service name label (default: cassandra)
 */
class JvmPauseDetector : Thread() {
    companion object {
        private val BUCKET_BOUNDARIES_MS =
            doubleArrayOf(0.1, 0.5, 1.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 5000.0, 10000.0)

        private fun prop(
            key: String,
            default: String,
        ): String = System.getProperty(key, default)
    }

    private val pollMs = prop("jvm.pause.agent.poll.ms", "100").toLong()
    private val otlpEndpoint = prop("jvm.pause.agent.otlp.endpoint", "http://localhost:4317")
    private val clusterName = prop("jvm.pause.agent.cluster.name", System.getenv("CLUSTER_NAME") ?: "unknown")
    private val serviceName = prop("jvm.pause.agent.service.name", "cassandra")

    private val histogram: DoubleHistogram

    init {
        val resource =
            Resource.builder()
                .put("service.name", serviceName)
                .put("host.name", java.net.InetAddress.getLocalHost().hostName)
                .put("cluster.name", clusterName)
                .build()

        val exporter =
            OtlpGrpcMetricExporter.builder()
                .setEndpoint(otlpEndpoint)
                .setTimeout(Duration.ofSeconds(10))
                .build()

        val metricReader =
            PeriodicMetricReader.builder(exporter)
                .setInterval(Duration.ofSeconds(10))
                .build()

        val meterProvider =
            SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(metricReader)
                .build()

        val meter = meterProvider.get("jvm-pause-agent")

        histogram =
            meter
                .histogramBuilder("jvm.pause.duration")
                .setDescription("JVM pause duration measured by sleep overshoot")
                .setUnit("ms")
                .setExplicitBucketBoundariesAdvice(BUCKET_BOUNDARIES_MS.toList())
                .build()
    }

    override fun run() {
        val attributes = Attributes.empty()
        while (!isInterrupted) {
            val beforeNs = System.nanoTime()
            try {
                TimeUnit.MILLISECONDS.sleep(pollMs)
            } catch (e: InterruptedException) {
                interrupt()
                break
            }
            val elapsedMs = (System.nanoTime() - beforeNs).toDouble() / 1_000_000.0
            val overshootMs = elapsedMs - pollMs
            if (overshootMs > 0) {
                histogram.record(overshootMs, attributes)
            }
        }
    }
}
