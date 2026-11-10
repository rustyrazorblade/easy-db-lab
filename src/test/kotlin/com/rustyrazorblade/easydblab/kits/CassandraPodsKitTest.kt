package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.KitMetrics
import com.rustyrazorblade.easydblab.services.KitType
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Checks the built-in `cassandra-pods` kit's cross-file contracts: the parsed `kit.yaml`, the
 * StatefulSet topology, how clients reach each pod, the init-container agent, and that stop keeps
 * the claims. Only the metadata of the real kit is asserted, never its scripts' wording.
 */
class CassandraPodsKitTest : BaseKoinTest() {
    private val kit by lazy {
        BuiltinKitFixture(
            "cassandra-pods",
            TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()),
        )
    }

    private fun statefulSet(args: Map<String, String> = emptyMap()): StatefulSet =
        kit.render("statefulset.yaml.template", mapOf("CASSANDRA_NODES" to "2") + args).filterIsInstance<StatefulSet>().single()

    private fun cassandra(): Container =
        statefulSet()
            .spec.template.spec.containers
            .single { it.name == "cassandra" }

    @Test
    fun `kit yaml declares a db kit with the six named args, the CQL endpoint and Java agent metrics`() {
        val args = kit.config.args.associateBy { it.flag }

        assertThat(kit.config.type).isEqualTo(KitType.DB)
        assertThat(args.keys).containsExactlyInAnyOrder("--version", "--image", "--nodes", "--heap", "--dc", "--storage-size")
        assertThat(args.getValue("--version").default).isEqualTo("5.0")
        assertThat(args.getValue("--image").default).isEmpty()
        assertThat(args.getValue("--nodes").default).isEqualTo("\${DB_NODE_COUNT}")
        assertThat(args.getValue("--dc").default).isEqualTo("dc1")
        assertThat(args.getValue("--storage-size").default).isEqualTo("10Ti")
        val cql = kit.config.endpoints.single { it.name == "CQL" }
        assertThat(cql.type).isEqualTo(KitEndpoint.EndpointType.NATIVE)
        assertThat(cql.nodeType).isEqualTo("db")
        assertThat(cql.port).isEqualTo(Constants.Kit.CassandraPods.CQL_NODE_PORT)
        assertThat(
            kit.config.metrics
                .filterIsInstance<KitMetrics.JavaAgent>()
                .single()
                .serviceName,
        ).isEqualTo("cassandra")
    }

    @Test
    fun `runs --nodes replicas one at a time, one per db node`() {
        val sts = statefulSet(mapOf("CASSANDRA_NODES" to "3"))
        val spec = sts.spec.template.spec

        assertThat(sts.spec.replicas).isEqualTo(3)
        assertThat(sts.spec.podManagementPolicy).isEqualTo("OrderedReady")
        assertThat(spec.nodeSelector).containsEntry("type", "db")
        val antiAffinity =
            spec.affinity.podAntiAffinity.requiredDuringSchedulingIgnoredDuringExecution
                .single()
        assertThat(antiAffinity.topologyKey).isEqualTo("kubernetes.io/hostname")
        assertThat(sts.spec.template.metadata.labels).containsAllEntriesOf(antiAffinity.labelSelector.matchLabels)
    }

    @Test
    fun `each pod advertises its node IP and listens on the NodePort value`() {
        val container = cassandra()
        val env = container.env.associateBy { it.name }
        val port = Constants.Kit.CassandraPods.CQL_NODE_PORT

        assertThat(
            env
                .getValue("HOST_IP")
                .valueFrom.fieldRef.fieldPath,
        ).isEqualTo("status.hostIP")
        assertThat(env.getValue("CASSANDRA_BROADCAST_RPC_ADDRESS").value).isEqualTo("$(HOST_IP)")
        assertThat(container.env.map { it.name }).containsSubsequence("HOST_IP", "CASSANDRA_BROADCAST_RPC_ADDRESS")
        assertThat(container.ports.map { it.containerPort }).contains(port)
        assertThat(container.command.joinToString(" ")).contains("native_transport_port: $port")
        assertThat(statefulSet().spec.template.spec).extracting("hostNetwork").isNotEqualTo(true)
        assertThat(container.ports.mapNotNull { it.hostPort }).isEmpty()
        assertThat(env.getValue("CASSANDRA_DC").value).isEqualTo("dc1")
        assertThat(env.getValue("CASSANDRA_RACK").value).isEqualTo("rack1")
        assertThat(env.getValue("CASSANDRA_ENDPOINT_SNITCH").value).isEqualTo("GossipingPropertyFileSnitch")
    }

    @Test
    fun `NodePort service maps the CQL port to itself and keeps traffic on the node`() {
        val services = kit.render("nodeport-service.yaml.template").filterIsInstance<Service>()
        val nodePort = services.single()
        val port = Constants.Kit.CassandraPods.CQL_NODE_PORT

        assertThat(nodePort.spec.type).isEqualTo("NodePort")
        assertThat(nodePort.spec.externalTrafficPolicy).isEqualTo("Local")
        assertThat(
            nodePort.spec.ports
                .single()
                .port,
        ).isEqualTo(port)
        assertThat(
            nodePort.spec.ports
                .single()
                .nodePort,
        ).isEqualTo(port)
        assertThat(
            nodePort.spec.ports
                .single()
                .targetPort.intVal,
        ).isEqualTo(port)
        assertThat(
            statefulSet()
                .spec.template.metadata.labels,
        ).containsAllEntriesOf(nodePort.spec.selector)
    }

    @Test
    fun `headless service gives stable DNS for the seed, including pods that are not ready`() {
        val headless = kit.render("headless-service.yaml.template").filterIsInstance<Service>().single()
        val sts = statefulSet()

        assertThat(headless.spec.clusterIP).isEqualTo("None")
        assertThat(headless.spec.publishNotReadyAddresses).isTrue()
        assertThat(sts.spec.serviceName).isEqualTo(headless.metadata.name)
        assertThat(cassandra().env.single { it.name == "CASSANDRA_SEEDS" }.value)
            .startsWith("${sts.metadata.name}-0.${headless.metadata.name}.")
    }

    @Test
    fun `the agent comes from an init container image tagged with the build's agent version, not the host`() {
        val spec = statefulSet().spec.template.spec
        val init = spec.initContainers.single()
        val agentVolume = spec.volumes.single { it.name == init.volumeMounts.single().name }

        assertThat(
            init.image,
        ).isEqualTo("${Constants.Kit.CassandraPods.AGENT_IMAGE_REPOSITORY}:${System.getProperty("easydblab.otelAgentVersion")}")
        assertThat(agentVolume.emptyDir).isNotNull()
        assertThat(spec.volumes.mapNotNull { it.hostPath?.path }).noneMatch { it.startsWith("/usr/local/otel") }
        assertThat(cassandra().volumeMounts.map { it.name }).contains(agentVolume.name)
        assertThat(cassandra().env.single { it.name == "JVM_EXTRA_OPTS" }.value).contains("-javaagent:")
    }

    @Test
    fun `every signal carries the node name, role and build the dashboards filter on`() {
        val env = cassandra().env.associateBy { it.name }

        assertThat(env.getValue("OTEL_SERVICE_NAME").value).isEqualTo("cassandra")
        assertThat(
            env
                .getValue("NODE_NAME")
                .valueFrom.fieldRef.fieldPath,
        ).isEqualTo("spec.nodeName")
        assertThat(cassandra().env.map { it.name }).containsSubsequence("NODE_NAME", "OTEL_RESOURCE_ATTRIBUTES")
        val attributes = env.getValue("OTEL_RESOURCE_ATTRIBUTES").value
        assertThat(attributes).contains("host.name=$(NODE_NAME)", "node_role=db", "cassandra_build=@RESOLVED_BUILD@")
        assertThat(env.getValue("OTEL_EXPORTER_OTLP_ENDPOINT").value).isEqualTo("http://$(HOST_IP):4318")
    }

    @Test
    fun `claims come from the kit's platform volumes`() {
        val sts = statefulSet()
        val pvs =
            kit.config.install
                .filterIsInstance<InstallStep.PlatformPvs>()
                .single()
        val claim = sts.spec.volumeClaimTemplates.single()

        assertThat(sts.metadata.name).isEqualTo("cassandra-pods")
        assertThat(claim.metadata.name).isEqualTo(pvs.volumeClaimTemplateName)
        assertThat(claim.spec.storageClassName).isEqualTo(pvs.storageClass)
        assertThat(claim.spec.selector.matchLabels).containsEntry("app.kubernetes.io/name", "cassandra-pods")
    }

    @Test
    fun `stop deletes by the kit label and never the claims, uninstall deletes the volumes`() {
        val stop =
            kit.config.stop
                .filterIsInstance<InstallStep.Delete>()
                .single { it.bySelector }

        assertThat(stop.selector).isEqualTo("easydblab/kit=cassandra-pods")
        assertThat(stop.kinds).contains("statefulset", "service", "configmap").doesNotContain("pvc", "persistentvolumeclaim")
        assertThat(kit.config.uninstall.filterIsInstance<InstallStep.PlatformPvsDelete>()).hasSize(1)
        assertThat(kit.config.runtime?.selector).isEqualTo("easydblab/kit=cassandra-pods")
    }

    @Test
    fun `the JMX rules are packaged from their single source`() {
        val source =
            CassandraPodsKitTest::class.java.classLoader
                .getResourceAsStream("com/rustyrazorblade/easydblab/configuration/cassandra/cassandra-jmx-rules.yaml")!!
                .readBytes()

        assertThat(kit.resource("cassandra-jmx-rules.yaml").toByteArray()).isEqualTo(source)
    }
}
