package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.otel.OtelEndpoints
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
 * traces go to the core collector Service, never a node IP, and the pods go to the db role's nodes. Every object
 * carries the kit and role labels `stop` deletes by. The scripts themselves are covered by the
 * cqlite-flight shell tests.
 */
class CqliteFlightKitTest : BaseKoinTest() {
    private val kit by lazy {
        BuiltinKitFixture("cqlite-flight", TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
    }

    private fun daemonSet(args: Map<String, String> = emptyMap()): DaemonSet =
        kit.render(MANIFEST, args).filterIsInstance<DaemonSet>().single()

    private fun service(args: Map<String, String> = emptyMap()): Service = kit.render(MANIFEST, args).filterIsInstance<Service>().single()

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
            service(mapOf("FLIGHT_PORT" to "9915"))
                .spec.ports
                .single()
                .port,
        ).isEqualTo(9915)
        assertThat(
            daemonSet()
                .spec.template.metadata.labels,
        ).containsAllEntriesOf(service.spec.selector)
    }

    @Test
    fun `telemetry goes to the core collector Service by default, never a node IP`() {
        val container = flightContainer()

        assertThat(container.env.map { it.name }).doesNotContain("HOST_IP")
        assertThat(env(container, "CQLITE_OTEL_ENDPOINT").value).isEqualTo(OtelEndpoints.otlpGrpcEndpoint())
        assertThat(env(flightContainer(mapOf("OTEL_ENDPOINT" to "http://elsewhere:4317")), "CQLITE_OTEL_ENDPOINT").value)
            .isEqualTo("http://elsewhere:4317")
        assertThat(env(container, "CQLITE_OTEL_PROTOCOL").value).isEqualTo("grpc")
    }

    @Test
    fun `pods go to the db role's recorded nodes, not a type label`() {
        assertThat(
            daemonSet()
                .spec.template.spec.nodeSelector,
        ).containsExactlyEntriesOf(mapOf("type" to "db"))
    }

    @Test
    fun `the image tag is required, so no run silently follows a moving tag`() {
        val tag = kit.config.args.single { it.variable == "TAG" }

        assertThat(tag.required).isTrue()
        assertThat(tag.default).isEmpty()
        assertThat(flightContainer(mapOf("TAG" to "v0.18.0")).image)
            .isEqualTo("ghcr.io/pmcfadin/cqlite-flight:v0.18.0")
    }

    @Test
    fun `a data root that does not exist fails the pod instead of being created`() {
        val hostPaths =
            daemonSet()
                .spec.template.spec.volumes
                .mapNotNull { it.hostPath }

        assertThat(hostPaths.map { it.path }).containsExactlyInAnyOrder("/mnt/db1/cassandra/data", "/mnt")
        assertThat(hostPaths.map { it.type }).containsOnly("Directory")
    }

    @Test
    fun `the multi-disk detector runs the script from the detector ConfigMap`() {
        val pod = daemonSet().spec.template.spec
        val detector = pod.initContainers.single { it.name == "detect-multidisk" }
        val scriptMount = detector.volumeMounts.single { it.name == DETECTOR_VOLUME }

        assertThat(detector.command).containsExactly("/bin/sh", "${scriptMount.mountPath}/$DETECTOR_SCRIPT")
        assertThat(
            pod.volumes
                .single { it.name == DETECTOR_VOLUME }
                .configMap.name,
        ).isEqualTo(DETECTOR_CONFIGMAP)
        assertThat(scriptMount.readOnly).isTrue()
    }

    @Test
    fun `the detector checks the served dir with the same mount and data GID the Flight server reads it with`() {
        val pod = daemonSet(mapOf("CASSANDRA_DATA_GID" to "1234")).spec.template.spec
        val detector = pod.initContainers.single { it.name == "detect-multidisk" }
        val flight = pod.containers.single { it.name == "cqlite-flight" }
        val flightData = flight.volumeMounts.single { it.name == DATA_VOLUME }
        val detectorData = detector.volumeMounts.single { it.name == DATA_VOLUME }

        assertThat(pod.securityContext.supplementalGroups).containsExactly(1234L)
        assertThat(env(detector, "DETECT_DATA_GID").value).isEqualTo("1234")
        assertThat(env(detector, "DETECT_DATA").value).isEqualTo(detectorData.mountPath)
        assertThat(detectorData.readOnly).isTrue().isEqualTo(flightData.readOnly)
        // No container-level identity, so both run as the pod's uid with --data-gid.
        assertThat(listOf(detector, flight).map { it.securityContext?.runAsUser }).containsOnlyNulls()
        assertThat(listOf(detector, flight).map { it.securityContext?.runAsGroup }).containsOnlyNulls()
        assertThat(env(detector, "DETECT_SERVED").value)
            .isEqualTo(
                pod.volumes
                    .single { it.name == DATA_VOLUME }
                    .hostPath.path,
            )
    }

    @Test
    fun `every object carries the kit and role labels stop deletes by`() {
        val objects = kit.render(MANIFEST)

        assertThat(objects.map { it.kind }).containsExactlyInAnyOrder("DaemonSet", "Service")
        objects.forEach { assertThat(it.metadata.labels).containsAllEntriesOf(STOP_LABELS) }
        assertThat(
            daemonSet()
                .spec.template.metadata.labels,
        ).containsAllEntriesOf(STOP_LABELS)
    }

    @Test
    fun `both containers run with no privilege escalation, no capabilities and a read-only root`() {
        val pod = daemonSet().spec.template.spec

        (pod.initContainers + pod.containers).forEach { container ->
            val security = requireNotNull(container.securityContext) { "${container.name} has no securityContext" }
            assertThat(security.allowPrivilegeEscalation).describedAs(container.name).isFalse()
            assertThat(security.capabilities.drop).describedAs(container.name).containsExactly("ALL")
            assertThat(security.capabilities.add.orEmpty()).describedAs(container.name).isEmpty()
            assertThat(security.readOnlyRootFilesystem).describedAs(container.name).isTrue()
            val tmp = container.volumeMounts.single { it.mountPath == "/tmp" }
            assertThat(pod.volumes.single { it.name == tmp.name }.emptyDir).describedAs(container.name).isNotNull()
            assertThat(tmp.readOnly == true).describedAs(container.name).isFalse()
        }
    }

    private companion object {
        const val MANIFEST = "daemonset.yaml.template"
        const val FLIGHT_NODE_PORT = 30816
        const val DATA_VOLUME = "cassandra-data"
        const val DETECTOR_VOLUME = "detect-multidisk-script"
        const val DETECTOR_SCRIPT = "detect-multidisk.sh"
        const val DETECTOR_CONFIGMAP = "cqlite-flight-detect-multidisk"

        /** The selector `stop` deletes by; `cqlite-flight-stop.test.sh` checks stop uses exactly it. */
        val STOP_LABELS = mapOf("easydblab/kit" to "cqlite-flight", "easydblab/role" to "flight")
    }
}
