package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.time.Duration

/**
 * Tests how a kit's declared endpoints resolve to addresses: a NodePort endpoint once per host
 * of its node type, case-insensitive on the node type, and nothing (rather than an exception)
 * for a node type the cluster does not know; a `pod-selector` endpoint only at the node that
 * runs its serving pod, and otherwise as unserved with the reason (#1224).
 */
class KitEndpointAddressesTest {
    @TempDir
    lateinit var workspace: File

    private val hosts =
        mapOf(
            ServerType.Cassandra to listOf(host("db0", "10.0.2.1"), host("db1", "10.0.2.2")),
            ServerType.Control to listOf(host("control0", "10.0.0.1")),
        )

    /** Answers no pod query: a NodePort endpoint must never ask the cluster. */
    private val noQueries = KitEndpointHost { selector, _ -> error("unexpected pod query for $selector") }

    private fun config(vararg endpoints: KitEndpoint) = KitConfig(name = "graph", endpoints = endpoints.toList())

    @Test
    fun `an unknown node type resolves to no addresses instead of throwing`() {
        assertThat(KitEndpointAddresses.privateIps("bogus", hosts)).isEmpty()
        assertThat(KitEndpointAddresses.resolve(config(endpoint("bogus")), "graph", hosts, noQueries))
            .isEqualTo(KitEndpointAddresses.Report(emptyList(), emptyList()))
    }

    @Test
    fun `node type is matched case-insensitively`() {
        assertThat(KitEndpointAddresses.privateIps("DB", hosts)).containsExactly("10.0.2.1", "10.0.2.2")
    }

    @Test
    fun `formatted lines carry name, lowercased type, and address, one per resolved host`() {
        val lines = KitEndpointAddresses.formatLines(KitEndpointAddresses.resolve(config(endpoint("db")), "graph", hosts, noQueries))

        assertThat(lines.lines()).hasSize(2)
        assertThat(lines.lines()[0]).contains("bolt", "native", "10.0.2.1:30687")
        assertThat(lines.lines()[1]).contains("bolt", "native", "10.0.2.2:30687")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resolveCases")
    fun `each endpoint is shown only at the nodes that serve it`(
        case: String,
        endpoint: KitEndpoint,
        pods: Result<List<KubernetesPod>>,
        expected: List<String>,
        unservedReason: String?,
    ) {
        val endpointHost = KitEndpointHost { _, _ -> pods }

        val report = KitEndpointAddresses.resolve(config(endpoint), "graph", hosts, endpointHost)

        assertThat(report.resolved.map { it.address }).`as`(case).containsExactlyElementsOf(expected)
        assertThat(report.unresolved.map { it.reason }).`as`(case).containsExactlyElementsOf(listOfNotNull(unservedReason))
    }

    @Test
    fun `an endpoint no pod serves is a line with its reason, after the served ones`() {
        val kit = config(endpoint("db"), selected.copy(name = "ui", port = 30474))
        val report = KitEndpointAddresses.resolve(kit, "graph", hosts, KitEndpointHost { _, _ -> Result.success(emptyList()) })

        val lines = KitEndpointAddresses.formatLines(report).lines()

        assertThat(lines).hasSize(3)
        assertThat(lines[2]).contains("ui", "not served", "no running, ready pod matches app=graph in namespace default")
    }

    @Test
    fun `the status summary groups endpoints that share a reason and points at the kit status`() {
        val unresolved =
            listOf(
                KitEndpointAddresses.Unresolved(selected, "no pod"),
                KitEndpointAddresses.Unresolved(selected.copy(name = "ui"), "no pod"),
                KitEndpointAddresses.Unresolved(selected.copy(name = "admin"), "proxy down"),
            )

        assertThat(KitEndpointAddresses.unavailableSummary("graph2", unresolved))
            .isEqualTo("bolt, ui: no pod; admin: proxy down; run 'graph2 status'")
        assertThat(KitEndpointAddresses.unavailableSummary("graph2", emptyList())).isNull()
    }

    @Test
    fun `a pod-selector endpoint is looked up with the kit name and in the runtime namespace`() {
        val queries = mutableListOf<Pair<String, String>>()
        val endpointHost =
            KitEndpointHost { selector, namespace ->
                queries += selector to namespace
                Result.success(listOf(servingPod("10.0.2.2")))
            }
        val kit =
            config(endpoint("db").copy(servingPodSelector = "app=\${KIT_NAME}"))
                .copy(runtime = KitRuntime(type = KitRuntime.RuntimeType.HELM, namespace = "graphs"))

        KitEndpointAddresses.resolve(kit, "graph2", hosts, endpointHost)

        assertThat(queries).containsExactly("app=graph2" to "graphs")
    }

    @Test
    fun `endpoints that share a pod selector are looked up once and shown at the same node`() {
        val queries = mutableListOf<Pair<String, String>>()
        val answers = ArrayDeque(listOf(listOf(servingPod("10.0.2.2")), listOf(servingPod("10.0.2.1"))))
        val endpointHost =
            KitEndpointHost { selector, namespace ->
                queries += selector to namespace
                Result.success(answers.removeFirst())
            }
        val kit = config(selected, selected.copy(name = "ui", port = 30474))

        val report = KitEndpointAddresses.resolve(kit, "graph", hosts, endpointHost)

        assertThat(queries).hasSize(1)
        assertThat(report.resolved.map { it.address }).containsExactly("10.0.2.2:30687", "10.0.2.2:30474")
    }

    @Test
    fun `an installed kit resolves its pod-selector endpoint by its directory name`() {
        val kitDir = File(workspace, "graph2").also { it.mkdirs() }
        File(kitDir, "kit.yaml").writeText(
            """
            name: graph
            endpoints:
              - name: bolt
                node-type: db
                port: 30687
                type: native
                pod-selector: app=${'$'}{KIT_NAME}
            """.trimIndent(),
        )
        val endpointHost =
            KitEndpointHost { selector, _ ->
                Result.success(if (selector == "app=graph2") listOf(servingPod("10.0.2.2")) else emptyList())
            }

        val resolved = KitEndpointAddresses.resolveInstalled(kitDir, hosts, endpointHost)

        assertThat(resolved.getOrThrow().resolved.map { it.address }).containsExactly("10.0.2.2:30687")
    }

    companion object {
        private fun host(
            alias: String,
            privateIp: String,
        ) = ClusterHost(
            publicIp = "3.4.5.6",
            privateIp = privateIp,
            alias = alias,
            availabilityZone = "us-west-2a",
            instanceId = "i-$alias",
        )

        private fun endpoint(nodeType: String) =
            KitEndpoint(name = "bolt", nodeType = nodeType, port = 30687, type = KitEndpoint.EndpointType.NATIVE)

        private fun servingPod(hostIp: String) =
            KubernetesPod(
                namespace = "default",
                name = "graph-0",
                status = "Running",
                ready = "1/1",
                restarts = 0,
                age = Duration.ofMinutes(1),
                hostIp = hostIp,
            )

        private val selected = endpoint("db").copy(servingPodSelector = "app=graph")

        @JvmStatic
        fun resolveCases(): List<Arguments> =
            listOf(
                Arguments.of(
                    "NodePort: every node of the type",
                    endpoint("db"),
                    Result.success(emptyList<KubernetesPod>()),
                    listOf("10.0.2.1:30687", "10.0.2.2:30687"),
                    null,
                ),
                Arguments.of(
                    "pod-selector: only the node running the pod, not the first node",
                    selected,
                    Result.success(listOf(servingPod("10.0.2.2"))),
                    listOf("10.0.2.2:30687"),
                    null,
                ),
                Arguments.of(
                    "pod-selector, no serving pod: not shown, with the reason",
                    selected,
                    Result.success(listOf(servingPod("10.0.2.2").copy(ready = "0/1"))),
                    emptyList<String>(),
                    "no running, ready pod matches app=graph in namespace default",
                ),
                Arguments.of(
                    "pod-selector, pod query fails: not shown, with the reason",
                    selected,
                    Result.failure<List<KubernetesPod>>(IllegalStateException("connection refused")),
                    emptyList<String>(),
                    "cannot list the pods matching app=graph in namespace default: connection refused",
                ),
                Arguments.of(
                    "pod-selector, the pod runs on a node of another type: not shown, with the reason",
                    selected,
                    Result.success(listOf(servingPod("10.0.0.1"))),
                    emptyList<String>(),
                    "the pods matching app=graph in namespace default run on 10.0.0.1, which is not a node of type db",
                ),
            )
    }
}
