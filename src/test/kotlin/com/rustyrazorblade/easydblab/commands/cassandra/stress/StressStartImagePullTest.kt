package com.rustyrazorblade.easydblab.commands.cassandra.stress

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.kubernetes.ImagePullFailure
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.services.DefaultStressJobService
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.StressJobService
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.batch.v1.Job
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * `stress start` with a stress image the node cannot pull: the real [DefaultStressJobService]
 * reports the pull failure once, as `Stress.ImagePullFailed`, and the command fails through
 * [CommandFailedException] so the executor does not print the cause a second time.
 */
class StressStartImagePullTest : BaseKoinTest() {
    private val k8sService: K8sService = mock()
    private val clusterStateManager: ClusterStateManager = mock()

    private val control =
        ClusterHost(publicIp = "54.1.2.3", privateIp = "10.0.1.5", alias = "control0", availabilityZone = "us-west-2a")
    private val db0 =
        ClusterHost(publicIp = "54.1.2.4", privateIp = "10.0.1.6", alias = "db0", availabilityZone = "us-west-2a")

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { clusterStateManager }
                single { TemplateService(get(), get()) }
                single<StressJobService> {
                    DefaultStressJobService(k8sService, get(), get(), get(), podReadyPollInterval = Duration.ZERO)
                }
            },
        )

    @Test
    fun `an image the node cannot pull is reported once and fails the command`() {
        whenever(clusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                infrastructure = InfrastructureState(vpcId = "vpc-test", region = "us-west-2"),
                hosts = mapOf(ServerType.Control to listOf(control), ServerType.Cassandra to listOf(db0)),
            ),
        )
        whenever(clusterStateManager.incrementStressJobCounter()).thenReturn(1)
        whenever(k8sService.createConfigMap(any(), any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(k8sService.createJob(any(), any(), any<Job>())).thenReturn(Result.success("job"))
        whenever(k8sService.getPodsForJob(any(), any(), any())).thenReturn(
            Result.success(
                listOf(
                    KubernetesPod(
                        namespace = "default",
                        name = "stress-pod",
                        status = "Pending",
                        ready = "0/2",
                        restarts = 0,
                        age = Duration.ZERO,
                        imagePullFailure = ImagePullFailure("stress", IMAGE, "ImagePullBackOff", CAUSE),
                    ),
                ),
            ),
        )
        val events = mutableListOf<Event>()
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events.add(envelope.event)
                }

                override fun close() = Unit
            },
        )
        val command = StressStart()
        command.stressArgs = listOf("KeyValue")

        assertThatThrownBy { command.execute() }
            .isInstanceOf(CommandFailedException::class.java)
            .extracting { it.message }
            .asString()
            .doesNotContain(CAUSE)

        assertThat(events.filterIsInstance<Event.Stress.ImagePullFailed>().single().image).isEqualTo(IMAGE)
        assertThat(events.filter { it.toDisplayString().contains(CAUSE) }).hasSize(1)
    }

    private companion object {
        const val IMAGE = "123456789012.dkr.ecr.us-west-2.amazonaws.com/stress:missing"
        const val CAUSE = "manifest for stress:missing not found"
    }
}
