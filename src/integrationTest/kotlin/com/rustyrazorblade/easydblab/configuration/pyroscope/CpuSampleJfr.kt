package com.rustyrazorblade.easydblab.configuration.pyroscope

import jdk.jfr.consumer.RecordingFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * A checked-in JFR chunk with a known number of CPU samples, for the tests that post a JFR to
 * Pyroscope and read back its CPU series.
 *
 * Those tests used to record the test JVM spinning for 1.5s with `jdk.ExecutionSample` at 10ms.
 * In the integration tier on CI that recording held no CPU samples, five times out of five. A
 * spin loop that runs interpreted, never JIT-compiled, gives exactly that: its time is spent in
 * the native `currentTimeMillis`, which JFR reports as `jdk.NativeMethodSample`, not as an
 * execution sample. A chunk with no samples still uploads (200) and Pyroscope stores nothing, so
 * the relay test read back an empty series. The fixture takes the test JVM's sampler and JIT out
 * of the test: it is a 3s recording of a JVM spinning, scrubbed with
 * `jfr scrub --include-events jdk.ExecutionSample` to just its [SAMPLES] CPU samples and JFR's own
 * type metadata.
 */
internal object CpuSampleJfr {
    private const val RESOURCE = "cpu-samples.jfr"
    private const val EXECUTION_SAMPLE = "jdk.ExecutionSample"

    /** The CPU samples the fixture holds, checked on every read so a replaced file cannot be empty. */
    const val SAMPLES = 65

    /** The fixture's bytes, after checking it holds [SAMPLES] CPU samples. */
    fun bytes(): ByteArray {
        val bytes =
            checkNotNull(CpuSampleJfr::class.java.getResourceAsStream(RESOURCE)) { "$RESOURCE is missing" }
                .use { it.readBytes() }
        val file = Files.createTempFile("cpu-samples", ".jfr")
        try {
            Files.write(file, bytes)
            val samples = samplesIn(file)
            check(samples == SAMPLES) { "$RESOURCE holds $samples CPU samples, not $SAMPLES" }
        } finally {
            Files.deleteIfExists(file)
        }
        return bytes
    }

    private fun samplesIn(file: Path): Int = RecordingFile.readAllEvents(file).count { it.eventType.name == EXECUTION_SAMPLE }
}
