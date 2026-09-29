package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.SparkService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/**
 * Step logs fetched through the real [S3ObjectStore] against LocalStack S3.
 *
 * `spark status --logs` saves a step's stderr under the local logs directory. EMR re-uploads that
 * stderr while the step runs and once more after it ends, so a later fetch must show the object
 * S3 holds now, not the copy an earlier fetch saved.
 */
class EMRSparkServiceStepLogsIntegrationTest {
    private val bucket = "emr-step-logs-test-bucket"
    private val clusterId = "j-STEPLOGS"
    private val stepId = "s-STEPLOGS"

    @Test
    fun `a later fetch of a step's stderr shows the copy S3 holds now, not the one saved earlier`(
        @TempDir tempDir: Path,
    ) {
        val s3Client = SharedLocalStack.s3Client()
        SharedLocalStack.createBucketIfMissing(s3Client, bucket)
        val clusterState = ClusterState(name = "test-cluster", clusterId = "c-steplogs", versions = mutableMapOf(), s3Bucket = bucket)
        val clusterStateManager = ClusterStateManager(File(tempDir.toFile(), "state.json")).also { it.save(clusterState) }
        val eventBus = EventBus()
        val objectStore = S3ObjectStore(s3Client, eventBus)
        val logsDir = tempDir.resolve("logs")
        val sparkService =
            EMRSparkService(
                mock(),
                objectStore,
                clusterStateManager,
                mock(),
                eventBus,
                logsDir = logsDir,
            )
        val stderrPath =
            ClusterS3Path
                .from(clusterState)
                .emrLogs()
                .resolve(clusterId)
                .resolve("steps")
                .resolve(stepId)
                .resolve(SparkService.LogType.STDERR.filename)

        objectStore.uploadFile(gzipped(tempDir, "partial", "INFO starting driver\n"), stderrPath, showProgress = false)
        val early = sparkService.fetchStepStderr(clusterId, stepId).getOrThrow()
        val finalStderr = "INFO starting driver\nException in thread \"main\" java.lang.IllegalStateException: keyspace missing\n"
        objectStore.uploadFile(gzipped(tempDir, "final", finalStderr), stderrPath, showProgress = false)

        val later = sparkService.fetchStepStderr(clusterId, stepId).getOrThrow()

        assertThat(early).isEqualTo(SparkService.StepStderr.Available("INFO starting driver\n"))
        assertThat(later).isEqualTo(SparkService.StepStderr.Available(finalStderr))
        assertThat(logsDir.resolve(clusterId).resolve(stepId).resolve("stderr")).hasContent(finalStderr)
    }

    private fun gzipped(
        dir: Path,
        name: String,
        content: String,
    ): File {
        val file = dir.resolve("$name.gz")
        GZIPOutputStream(Files.newOutputStream(file)).use { it.write(content.toByteArray()) }
        return file.toFile()
    }
}
