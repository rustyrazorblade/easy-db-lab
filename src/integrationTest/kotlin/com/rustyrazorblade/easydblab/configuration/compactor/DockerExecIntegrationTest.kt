package com.rustyrazorblade.easydblab.configuration.compactor

import com.rustyrazorblade.easydblab.ObservabilityBackends
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.images.RemoteDockerImage
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The exit code [execExitCode] reads is the command's, never a read taken while Docker still marks
 * the exec as running, which happens under concurrent execs and turned a ready lease agent into a
 * failed readiness check.
 */
class DockerExecIntegrationTest {
    @Test
    fun `under concurrent execs every exit code is the command's own`() {
        val docker = DockerClientFactory.instance().client()
        RemoteDockerImage(DockerImageName.parse(ObservabilityBackends.BUSYBOX_IMAGE)).get()
        val id =
            docker
                .createContainerCmd(ObservabilityBackends.BUSYBOX_IMAGE)
                .withCmd("/bin/busybox", "sleep", "600")
                .withLabels(DockerClientFactory.DEFAULT_LABELS)
                .exec()
                .id
        docker.startContainerCmd(id).exec()
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val codes =
                pool
                    .invokeAll(
                        (1..THREADS).map {
                            Callable {
                                (1..EXECS_PER_THREAD).map { n ->
                                    execExitCode(docker, id, listOf("/bin/busybox", "sh", "-c", "exit ${n % 2}")) to n % 2
                                }
                            }
                        },
                    ).flatMap { it.get() }

            assertThat(codes).allSatisfy { (actual, expected) -> assertThat(actual).isEqualTo(expected.toLong()) }
        } finally {
            pool.shutdownNow()
            docker.removeContainerCmd(id).withForce(true).exec()
        }
    }

    private companion object {
        const val THREADS = 16
        const val EXECS_PER_THREAD = 50
    }
}
