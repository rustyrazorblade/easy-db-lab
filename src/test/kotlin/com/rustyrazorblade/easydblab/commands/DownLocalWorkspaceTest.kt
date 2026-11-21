package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.services.TeardownBackupService
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.mock
import java.io.File

/**
 * `down` in a K3s in Docker workspace, for which easy-db-lab has no provisioner yet: it names the
 * environment and fails before it loads the profile, saves anything or calls AWS. Every AWS-facing
 * collaborator, and the SOCKS tunnel service, fails the test if it is touched.
 */
class DownLocalWorkspaceTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { ClusterStateManager(File(get<Context>().workingDirectory, "state.json")) }
                single<User> { error("down must not load the profile") }
                single<AwsInfrastructureService> { mock(defaultAnswer = { error("down must not tear down AWS") }) }
                single<AwsS3BucketService> { mock(defaultAnswer = { error("down must not call S3") }) }
                single<TeardownBackupService> { mock(defaultAnswer = { error("down must not save the tail") }) }
                // As in up, the guard comes before any tunnel: down neither starts nor stops one here.
                single<SocksProxyService> { mock(defaultAnswer = { error("down must not touch the SOCKS tunnel") }) }
            },
        )

    @Test
    fun `down reports no provisioner and leaves the workspace state as it was`() {
        val manager = get<ClusterStateManager>()
        val stateFile = File(context.workingDirectory, "state.json")
        manager.replace(
            ClusterState(name = "local", environment = Environment.K3sDocker("/data")).apply {
                markInfrastructureUp()
            },
        )
        val before = stateFile.readText()
        val events = captureEvents()

        assertThatThrownBy { Down().apply { autoApprove = true }.execute() }
            .isInstanceOf(CommandFailedException::class.java)

        assertThat(events).containsExactly(Event.Environment.ProvisionerUnavailable("down", "K3s in Docker"))
        assertThat(stateFile.readText()).isEqualTo(before)
    }

    private fun captureEvents(): List<Event> {
        val events = mutableListOf<Event>()
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
        return events
    }
}
