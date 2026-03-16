package com.rustyrazorblade.jvmpauseagent

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongHistogram
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ServiceAttributes
import java.time.Duration

/**
 * Measures JVM pauses by tracking sleep overshoot on a background thread.
 *
 * Every RESOLUTION_MS milliseconds, the thread sleeps and measures how much
 * longer than expected it slept. This overshoot captures GC stop-the-world
 * pauses, safepoint stalls, and OS scheduling jitter — anything that causes
 * the JVM to be unresponsive.
 *
 * Pause durations are emitted as a histogram metric (jvm.pause.duration, unit: ms)
 * via OTLP gRPC to the local OTel collector.
 */
class JvmPauseDetector(
    endpoint: String,
    clusterName: String,
    hostname: String,
) : Thread("jvm-pause-detector") {
    private val histogram: LongHistogram
    private val nodeAttributes: Attributes

    init {
        val resource = Resource.getDefault().merge(
            Resource.create(
                Attributes.of(ServiceAttributes.SERVICE_NAME, "jvm-pause-agent"),
            ),
        )

        val exporter = OtlpGrpcMetricExporter.builder()
            .setEndpoint(endpoint)
            .build()

        val reader = PeriodicMetricReader.builder(exporter)
            .setInterval(Duration.ofSeconds(REPORTING_INTERVAL_SECONDS))
            .build()

        val meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .setResource(resource)
            .build()

        val meter = meterProvider.get("jvm-pause-agent")

        histogram = meter.histogramBuilder("jvm.pause.duration")
            .setDescription("JVM pause duration measured via sleep overshoot (GC, safepoints, OS jitter)")
            .setUnit("ms")
            .ofLongs()
            .setExplicitBucketBoundariesAdvice(BUCKET_BOUNDARIES_MS)
            .build()

        nodeAttributes = Attributes.of(
            AttributeKey.stringKey("hostname"), hostname,
            AttributeKey.stringKey("cluster"), clusterName,
        )
    }

    override fun run() {
        while (!isInterrupted) {
            val sleepStart = System.nanoTime()
            try {
                sleep(RESOLUTION_MS)
            } catch (e: InterruptedException) {
                break
            }
            val elapsedMs = (System.nanoTime() - sleepStart) / NANOS_PER_MS
            val overshootMs = elapsedMs - RESOLUTION_MS
            if (overshootMs > 0) {
                histogram.record(overshootMs, nodeAttributes)
            }
        }
    }

    companion object {
        private const val RESOLUTION_MS = 100L
        private const val REPORTING_INTERVAL_SECONDS = 10L
        private const val NANOS_PER_MS = 1_000_000L

        private val BUCKET_BOUNDARIES_MS = listOf(
            1L, 2L, 5L, 10L, 25L, 50L, 100L, 250L, 500L, 1000L, 2500L, 5000L, 10000L, 30000L,
        )
    }
}
