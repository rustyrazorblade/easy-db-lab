package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.StressJobOutcome
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoute
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.Config
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StressJobListingTest : BaseKoinTest() {
    private fun job(
        startTime: String? = "2026-11-02T10:15:36Z",
        configure: JobBuilder.() -> JobBuilder = { this },
    ): Job =
        JobBuilder()
            .withNewMetadata()
            .withName("stress-1")
            .endMetadata()
            .withNewStatus()
            .withStartTime(startTime)
            .endStatus()
            .configure()
            .build()

    @Test
    fun `a job with no finished condition is running`() {
        assertThat(listedStressJob(job { editStatus().withActive(1).endStatus() }, "app0"))
            .isEqualTo(ListedStressJob.Running("stress-1", "app0"))
    }

    @Test
    fun `a Complete job succeeded, after its start to completion time`() {
        val complete =
            job {
                editStatus()
                    .withCompletionTime("2026-11-02T10:25:41Z")
                    .addNewCondition()
                    .withType("Complete")
                    .withStatus("True")
                    .withLastTransitionTime("2026-11-02T10:25:41Z")
                    .endCondition()
                    .endStatus()
            }

        assertThat(listedStressJob(complete, "app0"))
            .isEqualTo(ListedStressJob.Finished("stress-1", "app0", StressJobOutcome.SUCCEEDED, 605))
    }

    @Test
    fun `a Failed job failed, after its start to the failure time`() {
        val failed =
            job {
                editStatus()
                    .addNewCondition()
                    .withType("Failed")
                    .withStatus("True")
                    .withLastTransitionTime("2026-11-02T10:16:00Z")
                    .endCondition()
                    .endStatus()
            }

        assertThat(listedStressJob(failed, "app1"))
            .isEqualTo(ListedStressJob.Finished("stress-1", "app1", StressJobOutcome.FAILED, 24))
    }

    @Test
    fun `a finished condition that is not True leaves the job running`() {
        val notYet =
            job {
                editStatus()
                    .addNewCondition()
                    .withType("Failed")
                    .withStatus("False")
                    .endCondition()
                    .endStatus()
            }

        assertThat(listedStressJob(notYet, "app0")).isInstanceOf(ListedStressJob.Running::class.java)
    }

    @Test
    fun `a socks route sets an explicit per-client proxy and a direct route sets none`() {
        val socks = Config.empty().also { applyRoute(it, ObservabilityRoute.Socks(21000)) }
        val direct = Config.empty().also { it.httpsProxy = "socks5://stale:1" }.also { applyRoute(it, ObservabilityRoute.Direct) }

        assertThat(socks.httpsProxy).isEqualTo("socks5://${Constants.Proxy.TUNNEL_LISTEN_ADDRESS}:21000")
        assertThat(direct.httpsProxy).isNull()
        assertThat(System.getProperty("socksProxyHost")).isNull()
    }
}
