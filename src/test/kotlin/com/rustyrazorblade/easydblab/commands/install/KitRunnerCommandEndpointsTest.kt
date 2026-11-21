package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.get
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * After a successful start, the kit's declared endpoints are reported at their node's private
 * IP; reporting them never turns a successful start into a failure.
 */
class KitRunnerCommandEndpointsTest : KitRunnerCommandTestBase() {
    private val boltAndHttpEndpoints =
        """
        endpoints:
          - name: bolt
            node-type: db
            port: 30687
            type: native
          - name: http
            node-type: db
            port: 30474
            type: http
        """.trimIndent()

    private fun writeStartKitWithEndpoints() =
        writeKitYaml(
            "mydb",
            "name: mydb\n$boltAndHttpEndpoints\n" +
                """
                start:
                  - type: shell
                    script: echo hello
                """.trimIndent(),
        )

    @Test
    fun `successful start emits the declared endpoints at the node private IP`() {
        writeStartKitWithEndpoints()

        val events = captureEvents { command("mydb", "start").call() }

        val available = events.filterIsInstance<Event.Kit.EndpointsAvailable>().single()
        assertThat(available.kit).isEqualTo("mydb")
        assertThat(available.endpoints).containsExactly(
            Event.Kit.EndpointAddress(name = "bolt", type = "native", address = "10.0.2.1:30687"),
            Event.Kit.EndpointAddress(name = "http", type = "http", address = "http://10.0.2.1:30474"),
        )
        assertThat(available.toDisplayString()).contains("Endpoints:", "10.0.2.1:30687", "http://10.0.2.1:30474")
    }

    @Test
    fun `a one-pod endpoint is reported at the pod's node only, and as unserved with the reason when no pod serves`() {
        writeKitYaml(
            "mydb",
            """
            name: mydb
            endpoints:
              - name: ui
                node-type: db
                port: 8080
                type: http
                pod-selector: app=${'$'}{KIT_NAME}
            start:
              - type: shell
                script: echo hello
            """.trimIndent(),
        )
        val serving = KubernetesPod("default", "mydb-0", "Running", "1/1", 0, Duration.ofMinutes(1), hostIp = "10.0.2.1")
        whenever(mockKubeService.listPodsByLabel("app=mydb", "default"))
            .thenReturn(Result.success(listOf(serving)), Result.success(emptyList()))

        val served = captureEvents { assertThat(command("mydb", "start").call()).isEqualTo(0) }
        val unserved = captureEvents { assertThat(command("mydb", "start").call()).isEqualTo(0) }

        // The first listener stays on the bus, so it sees the second start too; its first event is the first start.
        assertThat(served.filterIsInstance<Event.Kit.EndpointsAvailable>().first()).isEqualTo(
            Event.Kit.EndpointsAvailable(
                kit = "mydb",
                endpoints = listOf(Event.Kit.EndpointAddress(name = "ui", type = "http", address = "http://10.0.2.1:8080")),
            ),
        )
        val notServed = unserved.filterIsInstance<Event.Kit.EndpointsAvailable>().single()
        assertThat(notServed.endpoints).isEmpty()
        assertThat(notServed.unserved).containsExactly(
            Event.Kit.UnservedEndpoint(name = "ui", reason = "no running, ready pod matches app=mydb in namespace default"),
        )
        assertThat(notServed.toDisplayString()).contains("ui", "not served", "no running, ready pod")
    }

    @Test
    fun `a failure reporting endpoints does not turn a successful start into a failure`() {
        writeStartKitWithEndpoints()
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    check(envelope.event !is Event.Kit.EndpointsAvailable) { "listener down" }
                }

                override fun close() = Unit
            },
        )

        val exitCode = command("mydb", "start").call()

        assertThat(exitCode).isEqualTo(0)
        verify(mockClusterStateManager).addRunningWorkload("mydb")
    }

    @Test
    fun `stop does not report endpoints`() {
        writeKitYaml(
            "mydb",
            "name: mydb\n$boltAndHttpEndpoints\n" +
                """
                stop:
                  - type: shell
                    script: echo bye
                """.trimIndent(),
        )
        val events = captureEvents { command("mydb", "stop").call() }
        assertThat(events.filterIsInstance<Event.Kit.EndpointsAvailable>()).isEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "",
            // The fixture cluster has control and db hosts only, so an app endpoint resolves to nothing.
            "endpoints:\n  - name: ui\n    node-type: app\n    port: 30080\n    type: http\n",
        ],
    )
    fun `start with no resolvable endpoint emits no EndpointsAvailable event`(endpoints: String) {
        writeKitYaml(
            "mydb",
            "name: mydb\n$endpoints" +
                """
                start:
                  - type: shell
                    script: echo hello
                """.trimIndent(),
        )

        val events = captureEvents { assertThat(command("mydb", "start").call()).isEqualTo(0) }

        assertThat(events.filterIsInstance<Event.Kit.EndpointsAvailable>()).isEmpty()
    }
}
