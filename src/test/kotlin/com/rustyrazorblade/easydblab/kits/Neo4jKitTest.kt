package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.CollisionCheck
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.KitMetrics
import com.rustyrazorblade.easydblab.services.KitType
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Checks the built-in Neo4j kit's cross-file contracts — image and version, the Java agent wiring
 * that makes metrics arrive under `job="neo4j"`, the NodePorts behind the declared endpoints, the
 * Bolt advertised address the start step publishes, and label-scoped cleanup — and runs the start
 * step's shell script against a stub `kubectl` to check its version gate and address.
 */
class Neo4jKitTest : BaseKoinTest() {
    private val kit by lazy {
        BuiltinKitFixture("neo4j", TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
    }

    private fun statefulSet(args: Map<String, String> = emptyMap()): StatefulSet =
        kit.render("statefulset.yaml.template", args).filterIsInstance<StatefulSet>().single()

    private fun neo4jContainer(args: Map<String, String> = emptyMap()): Container =
        statefulSet(args)
            .spec.template.spec.containers
            .single { it.name == "neo4j" }

    private fun env(
        container: Container,
        name: String,
    ): EnvVar = container.env.single { it.name == name }

    private fun nodePortService(): Service = kit.render("nodeport-service.yaml.template").filterIsInstance<Service>().single()

    private fun versionArg() = kit.config.args.single { it.flag == "--version" }

    private fun shellScript(steps: List<InstallStep>): String =
        steps
            .filterIsInstance<InstallStep.Shell>()
            .joinToString("\n") { it.script }

    @Test
    fun `is a db kit with a collision check`() {
        assertThat(kit.config.type).isEqualTo(KitType.DB)
        assertThat(kit.config.collisionCheck).isEqualTo(CollisionCheck.ENABLED)
    }

    @Test
    fun `runs one Community replica on a db node with no auth`() {
        val sts = statefulSet()

        assertThat(sts.spec.replicas).isEqualTo(1)
        assertThat(
            sts.spec.template.spec.affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution
                .nodeSelectorTerms
                .flatMap { it.matchExpressions },
        ).anySatisfy { expr ->
            assertThat(expr.key).isEqualTo("type")
            assertThat(expr.values).containsExactly("db")
        }
        assertThat(env(neo4jContainer(), "NEO4J_AUTH").value).isEqualTo("none")
    }

    /**
     * The image entrypoint turns every `NEO4J_*` env var into a config key, and strict validation
     * refuses unknown keys. Kubernetes injects `<SERVICE>_SERVICE_HOST`/`_PORT` vars for every
     * Service in the namespace, so the kit's own `neo4j-nodeport` Service became
     * `nodeport.service.port` and crash-looped the pod.
     */
    @Test
    fun `service links are off because a NEO4J_-prefixed Service name would crash the entrypoint`() {
        // Unset means true, so the field must be present and false. extracting() reads it without
        // Kotlin's null check on the platform type, so an unset field fails the assertion instead.
        assertThat(statefulSet().spec.template.spec)
            .extracting("enableServiceLinks")
            .isEqualTo(false)
    }

    /**
     * Neo4j accepts TCP on the Bolt port before it can run a query, so a TCP probe let `start`
     * report success while Cypher still failed. Readiness runs a query over Bolt instead.
     */
    @Test
    fun `the pod is ready only once Bolt answers a Cypher query`() {
        val probe = neo4jContainer().readinessProbe

        assertThat(probe.tcpSocket).isNull()
        assertThat(probe.exec.command).containsExactly("cypher-shell", "-a", "bolt://localhost:7687", "RETURN 1")
        // cypher-shell is a JVM client; the default one-second timeout would fail a healthy server.
        assertThat(probe.timeoutSeconds).isGreaterThanOrEqualTo(10)
        // Startup budget: the rollout waits up to 600s, and first start can take minutes.
        assertThat(probe.periodSeconds * probe.failureThreshold).isGreaterThanOrEqualTo(300)
    }

    @Nested
    inner class Version {
        @Test
        fun `default image is the pinned Community release`() {
            val default = versionArg().default

            assertThat(default).matches(SUPPORTED_VERSION)
            assertThat(neo4jContainer().image).isEqualTo("neo4j:$default-community")
        }

        @Test
        fun `--version selects the Community image`() {
            assertThat(neo4jContainer(mapOf(versionArg().variable to "5.26.0")).image).isEqualTo("neo4j:5.26.0-community")
        }
    }

    @Test
    fun `data lives on the platform PV that install provisions`() {
        val sts = statefulSet()
        val pvs =
            kit.config.install
                .filterIsInstance<InstallStep.PlatformPvs>()
                .single()
        val claim = sts.spec.volumeClaimTemplates.single()

        // platform-pvs names each PV <claim template>-<kit>-<ordinal>, the name the StatefulSet's PVC takes.
        assertThat(sts.metadata.name).isEqualTo("neo4j")
        assertThat(claim.metadata.name).isEqualTo(pvs.volumeClaimTemplateName)
        assertThat(claim.spec.storageClassName).isEqualTo(pvs.storageClass)
        assertThat(neo4jContainer().volumeMounts).anySatisfy { mount ->
            assertThat(mount.name).isEqualTo(claim.metadata.name)
            assertThat(mount.mountPath).isEqualTo("/data")
        }
    }

    @Nested
    inner class Metrics {
        @Test
        fun `the Java agent is mounted from the host and loaded by the JVM`() {
            val container = neo4jContainer()
            val otelVolume =
                statefulSet()
                    .spec.template.spec.volumes
                    .single { it.hostPath?.path == "/usr/local/otel" }
            val mount = container.volumeMounts.single { it.name == otelVolume.name }

            assertThat(mount.readOnly).isTrue()
            assertThat(env(container, "NEO4J_server_jvm_additional").value)
                .contains("-javaagent:${mount.mountPath}/opentelemetry-javaagent.jar")
        }

        @Test
        fun `metrics push to the collector on the pod's node under the declared service name`() {
            val container = neo4jContainer()
            val declared =
                kit.config.metrics
                    .filterIsInstance<KitMetrics.JavaAgent>()
                    .single()

            assertThat(declared.serviceName).isEqualTo("neo4j")
            assertThat(env(container, "OTEL_SERVICE_NAME").value).isEqualTo(declared.serviceName)
            assertThat(env(container, "HOST_IP").valueFrom.fieldRef.fieldPath).isEqualTo("status.hostIP")
            assertThat(container.env.map { it.name }).containsSubsequence("HOST_IP", "OTEL_EXPORTER_OTLP_ENDPOINT")
            assertThat(env(container, "OTEL_EXPORTER_OTLP_ENDPOINT").value).isEqualTo("http://$(HOST_IP):4318")
            assertThat(env(container, "OTEL_METRIC_EXPORT_INTERVAL").value).isEqualTo("5000")
        }

        /**
         * Left alone, the agent reports the pod hostname (`neo4j-0`) as host.name. The collector's
         * metrics/otlp pipeline overwrites it with the node, but the logs and traces pipelines do
         * not, so log-derived and span-derived series carried `neo4j-0` and fell out of every
         * node-keyed dashboard filter. Naming the node at the source makes every signal agree.
         */
        @Test
        fun `every signal names the node, not the pod, as host name`() {
            val container = neo4jContainer()

            assertThat(env(container, "NODE_NAME").valueFrom.fieldRef.fieldPath).isEqualTo("spec.nodeName")
            assertThat(container.env.map { it.name }).containsSubsequence("NODE_NAME", "OTEL_RESOURCE_ATTRIBUTES")
            assertThat(env(container, "OTEL_RESOURCE_ATTRIBUTES").value).isEqualTo("host.name=$(NODE_NAME)")
        }
    }

    @Test
    fun `Bolt and HTTP endpoints are the NodePorts that forward to Neo4j`() {
        val service = nodePortService()
        val containerPorts = neo4jContainer().ports.associate { it.name to it.containerPort }
        val bolt = kit.config.endpoints.single { it.type == KitEndpoint.EndpointType.NATIVE }
        val http = kit.config.endpoints.single { it.type == KitEndpoint.EndpointType.HTTP }

        assertThat(listOf(bolt, http)).allSatisfy { assertThat(it.nodeType).isEqualTo("db") }
        assertThat(bolt.port).isEqualTo(BOLT_NODE_PORT)
        assertThat(http.port).isEqualTo(30474)
        assertThat(service.spec.type).isEqualTo("NodePort")
        assertThat(
            service.spec.ports
                .single { it.nodePort == bolt.port }
                .targetPort.intVal,
        ).isEqualTo(containerPorts["bolt"])
        assertThat(
            service.spec.ports
                .single { it.nodePort == http.port }
                .targetPort.intVal,
        ).isEqualTo(containerPorts["http"])
        assertThat(containerPorts).containsEntry("bolt", 7687).containsEntry("http", 7474)
        assertThat(
            statefulSet()
                .spec.template.metadata.labels,
        ).containsAllEntriesOf(service.spec.selector)
    }

    @Nested
    inner class AdvertisedAddress {
        private val stub by lazy { StubKubectl(File(tempDir, "stub")) }

        private fun runStartScript(
            version: String,
            dbNodeIps: String = "10.0.1.10,10.0.2.10",
        ) = stub.run(
            script =
                kit.config.start
                    .filterIsInstance<InstallStep.Shell>()
                    .first()
                    .script,
            env = mapOf("DB_NODE_IPS" to dbNodeIps, versionArg().variable to version),
        )

        @Test
        fun `start publishes the first db node's IP and the Bolt NodePort`() {
            val exit = runStartScript("5.26.0")

            assertThat(exit).isEqualTo(0)
            // The ConfigMap key is the Neo4j env var itself, loaded with envFrom: the name holds a
            // double underscore, which the kit template engine would read as a placeholder.
            val configMap =
                neo4jContainer()
                    .envFrom
                    .single()
                    .configMapRef.name
            assertThat(stub.invocations()).anySatisfy { call ->
                assertThat(call).contains("create configmap $configMap")
                assertThat(call).contains("--from-literal=NEO4J_server_bolt_advertised__address=10.0.1.10:$BOLT_NODE_PORT")
            }
        }

        @Test
        fun `the advertised-address ConfigMap carries the kit label that stop and uninstall delete by`() {
            runStartScript("5.26.0")

            assertThat(stub.invocations()).anySatisfy { call ->
                assertThat(call).contains("label --local -f - $KIT_LABEL=neo4j")
            }
            assertThat(shellScript(kit.config.stop)).contains("configmap", "-l $KIT_LABEL=neo4j")
            assertThat(shellScript(kit.config.uninstall)).contains("configmap", "-l $KIT_LABEL=neo4j")
        }

        @Test
        fun `start fails with an error instead of publishing a bare port when there is no db node IP`() {
            val exit = runStartScript("5.26.0", dbNodeIps = "")

            assertThat(exit).isNotEqualTo(0)
            assertThat(stub.output()).contains("ERROR: no db node IP found in DB_NODE_IPS.")
            assertThat(stub.invocations()).isEmpty()
        }

        @Test
        fun `start accepts 5 6 0 and later and calendar-versioned releases, including the default`() {
            assertThat(listOf(FIRST_SUPPORTED_5X, "5.10.0", "5.26.0", "2025.05.0", "2026.09.0", versionArg().default))
                .allSatisfy { version -> assertThat(runStartScript(version)).isEqualTo(0) }
        }

        @Test
        fun `start refuses 5 x releases whose entrypoint replaces the stock JVM flags, naming the first supported one`() {
            assertThat(listOf("5.5.0", "5.1.0", "5.0.0"))
                .allSatisfy { version ->
                    assertThat(runStartScript(version)).isNotEqualTo(0)
                    assertThat(stub.output())
                        .contains("ERROR:", "'$version'", FIRST_SUPPORTED_5X, "server.jvm.additional")
                }
            assertThat(stub.invocations()).isEmpty()
        }

        @Test
        fun `start refuses 4 x and earlier before touching the cluster`() {
            assertThat(listOf("4.4.0", "4.4.30", "3.5.35"))
                .allSatisfy { version -> assertThat(runStartScript(version)).isNotEqualTo(0) }
            assertThat(stub.invocations()).isEmpty()
        }
    }

    /**
     * Runs the stop and uninstall shell steps against a stub `kubectl`. Deleting by a label that
     * matches nothing makes kubectl print a bare "No resources found", so the steps look the
     * objects up first and delete only what the label selects.
     */
    @Nested
    inner class LabelScopedCleanup {
        private val stub by lazy { StubKubectl(File(tempDir, "stub")) }

        private val phases by lazy { mapOf("stop" to kit.config.stop, "uninstall" to kit.config.uninstall) }

        private fun run(steps: List<InstallStep>): Int = stub.run(script = shellScript(steps), env = emptyMap())

        private fun deletes() = stub.invocations().filter { it.startsWith("delete") }

        @Test
        fun `stop and uninstall print nothing and delete nothing once the kit is gone`() {
            stub.respondToGet("")

            for ((phase, steps) in phases) {
                assertThat(run(steps)).describedAs(phase).isEqualTo(0)
                assertThat(stub.output()).describedAs(phase).isEmpty()
            }
            assertThat(deletes()).isEmpty()
        }

        @Test
        fun `stop and uninstall delete exactly the objects the kit label selects`() {
            stub.respondToGet("statefulset.apps/neo4j\nservice/neo4j\n")

            for ((phase, steps) in phases) {
                assertThat(run(steps)).describedAs(phase).isEqualTo(0)
            }
            assertThat(stub.invocations().filter { it.startsWith("get") })
                .hasSize(2)
                .allSatisfy { assertThat(it).contains("-l $KIT_LABEL=neo4j") }
            assertThat(deletes())
                .hasSize(2)
                .allSatisfy { assertThat(it).contains("statefulset.apps/neo4j service/neo4j") }
        }

        @Test
        fun `a failed lookup fails the step instead of reporting a clean stop`() {
            stub.respondToGet("", exitCode = 1)

            for ((phase, steps) in phases) {
                assertThat(run(steps)).describedAs(phase).isNotEqualTo(0)
            }
            assertThat(deletes()).isEmpty()
        }
    }

    @Test
    fun `every object carries the kit label that stop and uninstall select on`() {
        val objects: List<HasMetadata> = kit.render("statefulset.yaml.template") + kit.render("nodeport-service.yaml.template")
        val sts = statefulSet()

        assertThat(objects).allSatisfy { assertThat(it.metadata.labels).containsEntry(KIT_LABEL, "neo4j") }
        assertThat(sts.spec.template.metadata.labels).containsEntry(KIT_LABEL, "neo4j")
        assertThat(
            sts.spec.volumeClaimTemplates
                .single()
                .metadata.labels,
        ).containsEntry(KIT_LABEL, "neo4j")

        assertThat(shellScript(kit.config.stop)).contains("statefulset", "service", "pod", "-l $KIT_LABEL=neo4j")
        assertThat(shellScript(kit.config.uninstall)).contains("pvc", "-l $KIT_LABEL=neo4j")
        assertThat(kit.config.uninstall).anyMatch { it is InstallStep.PlatformPvsDelete }
    }

    private companion object {
        const val KIT_LABEL = "easydblab/kit"
        const val BOLT_NODE_PORT = 30687

        // The first neo4j:5.x image whose docker-entrypoint.sh appends server.jvm.additional
        // instead of replacing it (docker-neo4j commit e1ebd21a).
        const val FIRST_SUPPORTED_5X = "5.6.0"
        val SUPPORTED_VERSION = Regex("""^(5|20[2-9]\d)\.\d+\.\d+$""").toPattern()
    }
}

/**
 * Runs a kit shell step the way `WorkloadStepExecutor` does, with a stub `kubectl` first on
 * `PATH` that records each invocation's arguments and passes stdin through, so a test can check
 * what the step would have applied without a cluster. [respondToGet] scripts what `kubectl get`
 * prints and how it exits.
 */
class StubKubectl(
    private val dir: File,
) {
    private val log = File(dir, "kubectl.log")
    private val out = File(dir, "script.out")
    private val getOut = File(dir, "get.out")
    private val getExit = File(dir, "get.exit")

    init {
        dir.mkdirs()
        File(dir, "kubectl").apply {
            writeText(
                """
                #!/bin/bash
                echo "$*" >> "${log.absolutePath}"
                if [ "$1" = "get" ] && [ -f "${getOut.absolutePath}" ]; then
                  cat "${getOut.absolutePath}"
                  exit "$(cat "${getExit.absolutePath}")"
                fi
                cat
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
    }

    /** Makes every later `kubectl get` print [output] and exit with [exitCode]. */
    fun respondToGet(
        output: String,
        exitCode: Int = 0,
    ) {
        getOut.writeText(output)
        getExit.writeText(exitCode.toString())
    }

    /** Runs [script] under bash with [env] added, returning the exit code; see [output]. */
    fun run(
        script: String,
        env: Map<String, String>,
    ): Int =
        ProcessBuilder("bash", "-c", script)
            .directory(dir)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectErrorStream(true)
            .redirectOutput(out)
            .also { pb ->
                pb.environment().putAll(env)
                pb.environment()["PATH"] = "${dir.absolutePath}:${System.getenv("PATH")}"
            }.start()
            .waitFor()

    /** The last run's combined stdout and stderr. */
    fun output(): String = if (out.isFile) out.readText() else ""

    /** Each recorded `kubectl` invocation's arguments, one per call. */
    fun invocations(): List<String> = if (log.isFile) log.readLines() else emptyList()
}
