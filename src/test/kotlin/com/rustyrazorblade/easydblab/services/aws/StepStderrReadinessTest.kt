package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.services.ObjectStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * EMR uploads a running step's stderr every few minutes and once more after the step ends. In
 * F14 the copy uploaded at 14:08:45 was printed, the driver failed at 14:08:51, and the copy with
 * the exception arrived at 14:13:48.
 */
class StepStderrReadinessTest {
    private val stepEnd = Instant.parse("2026-09-28T14:08:52Z")

    private fun uploadedAt(time: String) =
        ObjectStore.FileInfo(ClusterS3Path.fromUri("s3://bucket/spark/emr-logs/j-1/steps/s-1/stderr.gz"), size = 1, lastModified = time)

    @Test
    fun `a copy uploaded before the step ended is not final`() {
        assertThat(StepStderrReadiness.isFinal(uploadedAt("2026-09-28T14:08:45Z"), stepEnd)).isFalse()
    }

    @Test
    fun `a copy uploaded after the step ended is final`() {
        assertThat(StepStderrReadiness.isFinal(uploadedAt("2026-09-28T14:13:48Z"), stepEnd)).isTrue()
    }

    @Test
    fun `no copy yet is not final`() {
        assertThat(StepStderrReadiness.isFinal(null, stepEnd)).isFalse()
    }

    @Test
    fun `without the step's end time, any uploaded copy is taken as final`() {
        assertThat(StepStderrReadiness.isFinal(uploadedAt("2026-09-28T14:08:45Z"), null)).isTrue()
    }
}
