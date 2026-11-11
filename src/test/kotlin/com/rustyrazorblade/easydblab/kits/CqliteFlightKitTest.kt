package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Checks the built-in cqlite-flight kit's cross-file contracts. Each Flight pod serves only the
 * SSTables of the db node it runs on, so a client must reach the pod of one particular node: the
 * pods run on the pod network (never `hostNetwork` or a `hostPort`), and a NodePort Service with
 * `externalTrafficPolicy: Local` sends `<db-node-ip>:<nodePort>` to that node's own pod. Metrics and
 * traces go to the collector on the pod's node through the downward-API host IP, and `stop`
 * deletes by the label every object carries.
 */
class CqliteFlightKitTest : BaseKoinTest() {
    private val kit by lazy {
        BuiltinKitFixture("cqlite-flight", TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
    }

    private fun daemonSet(args: Map<String, String> = emptyMap()): DaemonSet =
        kit.render(MANIFEST, args).filterIsInstance<DaemonSet>().single()

    private fun service(): Service = kit.render(MANIFEST).filterIsInstance<Service>().single()

    private fun flightContainer(args: Map<String, String> = emptyMap()): Container =
        daemonSet(args)
            .spec.template.spec.containers
            .single { it.name == "cqlite-flight" }

    private fun env(
        container: Container,
        name: String,
    ): EnvVar = container.env.single { it.name == name }

    @Test
    fun `pods run on the pod network with no host port`() {
        val pod = daemonSet().spec.template.spec

        assertThat(pod.hostNetwork == true).isFalse()
        assertThat(pod.dnsPolicy).isNotEqualTo("ClusterFirstWithHostNet")
        assertThat((pod.containers + pod.initContainers).flatMap { it.ports.orEmpty() }.mapNotNull { it.hostPort }).isEmpty()
    }

    @Test
    fun `the Flight endpoint is a node-local NodePort that forwards to the Flight container port`() {
        val service = service()
        val endpoint = kit.config.endpoints.single { it.type == KitEndpoint.EndpointType.NATIVE }
        val flightPort =
            flightContainer(mapOf("FLIGHT_PORT" to "9915"))
                .ports
                .single { it.name == "flight-grpc" }

        assertThat(endpoint.nodeType).isEqualTo("db")
        assertThat(service.spec.type).isEqualTo("NodePort")
        assertThat(service.spec.externalTrafficPolicy).isEqualTo("Local")
        val port = service.spec.ports.single()
        assertThat(port.nodePort).isEqualTo(endpoint.port).isEqualTo(FLIGHT_NODE_PORT)
        assertThat(port.targetPort.strVal).isEqualTo("flight-grpc")
        assertThat(flightPort.containerPort).isEqualTo(9915)
        assertThat(
            daemonSet()
                .spec.template.metadata.labels,
        ).containsAllEntriesOf(service.spec.selector)
    }

    @Test
    fun `telemetry goes to the collector on the pod's node`() {
        val container = flightContainer()

        assertThat(env(container, "HOST_IP").valueFrom.fieldRef.fieldPath).isEqualTo("status.hostIP")
        assertThat(container.env.map { it.name }).containsSubsequence("HOST_IP", "CQLITE_OTEL_ENDPOINT")
        assertThat(env(container, "CQLITE_OTEL_ENDPOINT").value).isEqualTo("http://$(HOST_IP):4317")
        assertThat(env(container, "CQLITE_OTEL_PROTOCOL").value).isEqualTo("grpc")
    }

    @Test
    fun `stop deletes the DaemonSet and the Service by the label both carry`() {
        val stop = kit.resource("bin/stop.sh.template")
        val selector =
            requireNotNull(SELECTOR.find(stop)) { "stop.sh deletes by no easydblab/kit selector" }
                .groupValues[1]
                .replace("__KIT_NAME__", "cqlite-flight")
        val labels = parseLabelSelector(selector)

        assertThat(stop).contains("delete daemonset,service")
        assertThat(daemonSet().metadata.labels).containsAllEntriesOf(labels)
        assertThat(service().metadata.labels).containsAllEntriesOf(labels)
    }

    private companion object {
        const val MANIFEST = "daemonset.yaml.template"
        const val FLIGHT_NODE_PORT = 30815
        val SELECTOR = Regex("""LABELS="(easydblab/kit=[^"]+)"""")
    }
}
