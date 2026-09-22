package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
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
        assertThat(kit.config.collisionCheck).isTrue()
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
        fun `start accepts 5 x and calendar-versioned releases, including the default`() {
            assertThat(listOf("5.26.0", "2025.05.0", "2026.09.0", versionArg().default))
                .allSatisfy { version -> assertThat(runStartScript(version)).isEqualTo(0) }
        }

        @Test
        fun `start refuses 4 x and earlier before touching the cluster`() {
            assertThat(listOf("4.4.0", "4.4.30", "3.5.35"))
                .allSatisfy { version -> assertThat(runStartScript(version)).isNotEqualTo(0) }
            assertThat(stub.invocations()).isEmpty()
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
        val SUPPORTED_VERSION = Regex("""^(5|20[2-9]\d)\.\d+\.\d+$""").toPattern()
    }
}

/**
 * Runs a kit shell step the way `WorkloadStepExecutor` does, with a stub `kubectl` first on
 * `PATH` that records each invocation's arguments and passes stdin through, so a test can check
 * what the step would have applied without a cluster.
 */
class StubKubectl(
    private val dir: File,
) {
    private val log = File(dir, "kubectl.log")
    private val out = File(dir, "script.out")

    init {
        dir.mkdirs()
        File(dir, "kubectl").apply {
            writeText("#!/bin/bash\necho \"$*\" >> \"${log.absolutePath}\"\ncat\n")
            setExecutable(true)
        }
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
