package com.rustyrazorblade.easydblab.services

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The workspace locator finds the kubeconfig in the workspace and the context pinned in cluster
 * state, whatever the process's current directory or the user's kubectl context is.
 */
class WorkspaceKubeApiLocatorTest {
    @TempDir
    lateinit var workspace: File

    private val kubeconfig get() = File(workspace, Constants.K3s.LOCAL_KUBECONFIG)

    private fun writeKubeconfig(currentContext: String?) {
        val current = currentContext?.let { "current-context: $it\n" }.orEmpty()
        kubeconfig.writeText(
            """
            |apiVersion: v1
            |kind: Config
            |clusters:
            |- name: c
            |  cluster:
            |    server: https://10.0.0.1:6443
            |contexts:
            |- name: default
            |  context:
            |    cluster: c
            |    user: u
            |- name: pinned
            |  context:
            |    cluster: c
            |    user: u
            |$current
            |users:
            |- name: u
            |  user:
            |    token: t
            """.trimMargin(),
        )
    }

    private fun locator(recorded: String?): WorkspaceKubeApiLocator {
        val manager = ClusterStateManager(File(workspace, "state.json"))
        manager.save(ClusterState(name = "test", versions = mutableMapOf()).apply { kubeContext = recorded })
        return WorkspaceKubeApiLocator(manager, workspace)
    }

    @Test
    fun `the context recorded in cluster state wins over the kubeconfig's current-context`() {
        writeKubeconfig(currentContext = "default")

        assertThat(locator(recorded = "pinned").locate()).isEqualTo(KubeApi(kubeconfig.toPath(), "pinned"))
    }

    @Test
    fun `without a recorded context the kubeconfig's current-context is used`() {
        writeKubeconfig(currentContext = "default")

        assertThat(locator(recorded = null).locate()).isEqualTo(KubeApi(kubeconfig.toPath(), "default"))
    }

    @Test
    fun `no recorded context and no current-context fails naming the kubeconfig`() {
        writeKubeconfig(currentContext = null)

        assertThatThrownBy { locator(recorded = null).locate() }
            .hasMessageContaining("current-context")
            .hasMessageContaining(kubeconfig.absolutePath)
    }

    @Test
    fun `a missing kubeconfig names the workspace path`() {
        assertThatThrownBy { locator(recorded = "pinned").locate() }
            .hasMessageContaining(kubeconfig.absolutePath)
    }

    /** Runs [block] and returns the events of [Level.INFO] or above the locator logged meanwhile. */
    private fun infoLogsDuring(block: () -> Unit): List<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(WorkspaceKubeApiLocator::class.java) as Logger
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level.isGreaterOrEqual(Level.INFO) }
    }

    @Test
    fun `falling back to the kubeconfig's current-context logs the context and the kubeconfig once`() {
        writeKubeconfig(currentContext = "default")
        val locator = locator(recorded = null)

        val events = infoLogsDuring { repeat(3) { locator.locate() } }

        assertThat(events).singleElement().satisfies({ event ->
            assertThat(event.formattedMessage)
                .contains("'default'")
                .contains(kubeconfig.absolutePath)
        })
    }

    @Test
    fun `a fallback to a different current-context is logged again`() {
        writeKubeconfig(currentContext = "default")
        val locator = locator(recorded = null)

        val events =
            infoLogsDuring {
                locator.locate()
                writeKubeconfig(currentContext = "pinned")
                locator.locate()
            }

        assertThat(events.map { it.formattedMessage })
            .satisfiesExactly(
                { assertThat(it).contains("'default'") },
                { assertThat(it).contains("'pinned'") },
            )
    }

    @Test
    fun `a recorded context logs no fallback`() {
        writeKubeconfig(currentContext = "default")

        assertThat(infoLogsDuring { locator(recorded = "pinned").locate() }).isEmpty()
    }
}
