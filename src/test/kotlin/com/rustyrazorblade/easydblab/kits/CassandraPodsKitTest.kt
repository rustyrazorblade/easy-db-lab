package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.agents.JavaAgent
import com.rustyrazorblade.easydblab.configuration.agents.JavaAgentDelivery
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.KitMetrics
import com.rustyrazorblade.easydblab.services.KitType
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Checks the built-in `cassandra-pods` kit's cross-file contracts: the parsed `kit.yaml`, the two
 * StatefulSet templates as `lib/apply-statefulset.sh` fills them in (the tarball path, and the
 * `--image` path), how clients reach each pod, the init containers, and that stop keeps the claims.
 * Only the metadata of the real kit is asserted, never its scripts' wording.
 */
class CassandraPodsKitTest : BaseKoinTest() {
    private val kit by lazy {
        BuiltinKitFixture(
            "cassandra-pods",
            TemplateService(ClusterStateManager(File(tempDir, "state.json"))),
        )
    }

    private val sha = "c".repeat(64)

    /** A presigned URL: `&` and `|` would break a naive sed substitution. */
    private val presigned = "https://bucket.s3.amazonaws.com/c.tar.gz?X-Amz-Expires=604800&X-Amz-Signature=a|b"

    private val guaranteed = """{"requests":{"cpu":"7","memory":"22500Mi"},"limits":{"cpu":"7","memory":"22500Mi"}}"""
    private val guaranteedInit = """{"requests":{"cpu":"1","memory":"512Mi"},"limits":{"cpu":"1","memory":"512Mi"}}"""
    private val burstable = """{"requests":{"cpu":"7","memory":"3Gi"},"limits":{"memory":"3Gi"}}"""

    /** What the `cassandra-artifact` contributor puts in the environment of a tarball-path start. */
    private fun artifactEnv(vararg overrides: Pair<String, String>): Map<String, String> =
        mapOf(
            "EDL_TARBALL_URL" to presigned,
            "EDL_TARBALL_FALLBACK_URL" to "",
            "EDL_TARBALL_SHA256" to sha,
            "EDL_CASSANDRA_BUILD" to "5.0.6",
            "EDL_RUNTIME_IMAGE" to "ghcr.io/rustyrazorblade/cassandra-runtime:1-jre17",
            "EDL_CONFIG_HASH" to "0123456789abcdef",
            "EDL_CASSANDRA_RESOURCES" to guaranteed,
            "EDL_INIT_RESOURCES" to guaranteedInit,
            "CASSANDRA_IMAGE" to "",
        ) + overrides

    /** What a `--image` start has: the contributor's sizing, and `resolved-image.env` from the start check. */
    private fun imageEnv(vararg overrides: Pair<String, String>): Map<String, String> =
        mapOf(
            "CASSANDRA_IMAGE" to "ghcr.io/example/cassandra:sha-abc",
            "EDL_CASSANDRA_RESOURCES" to guaranteed,
            "EDL_INIT_RESOURCES" to guaranteedInit,
        ) + overrides

    private var applies = 0

    /** The result of running `lib/apply-statefulset.sh` in a kit directory holding both rendered templates. */
    private class Applied(
        val exitCode: Int,
        val output: String,
        val manifest: String?,
    ) {
        fun statefulSet(): StatefulSet = parseManifests(checkNotNull(manifest) { output }).filterIsInstance<StatefulSet>().single()

        /** The tarball path's artifact Secret, which holds the URLs the fetch reads. */
        fun artifactSecret(): Secret = parseManifests(checkNotNull(manifest) { output }).filterIsInstance<Secret>().single()
    }

    private fun apply(
        env: Map<String, String> = artifactEnv(),
        args: Map<String, String> = emptyMap(),
        resolvedImage: String? = "RESOLVED_IMAGE='ghcr.io/example/cassandra:sha-abc'\nRESOLVED_BUILD='sha-abc'\n",
    ): Applied {
        val dir = File(tempDir, "kit-${applies++}").apply { mkdirs() }
        val rendered = mapOf("CASSANDRA_NODES" to "2") + args
        for (template in listOf(TARBALL_TEMPLATE, IMAGE_TEMPLATE)) {
            File(dir, template.removeSuffix(".template")).writeText(kit.renderText(template, rendered))
        }
        File(dir, "lib").mkdirs()
        File(dir, "lib/apply-statefulset.sh").writeText(kit.resource("lib/apply-statefulset.sh"))
        resolvedImage?.let { File(dir, "resolved-image.env").writeText(it) }
        val stubs = File(dir, "stubs").apply { mkdirs() }
        val manifest = File(dir, "applied.yaml")
        File(stubs, "kubectl").apply {
            writeText("#!/bin/sh\n[ \"$1 $2 $3\" = \"apply -f -\" ] || exit 9\ncat > '${manifest.absolutePath}'\n")
            setExecutable(true)
        }
        val process =
            ProcessBuilder("bash", "lib/apply-statefulset.sh")
                .directory(dir)
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(env)
                    environment()["PATH"] = "${stubs.absolutePath}:${System.getenv("PATH")}"
                }.start()
        val output = process.inputStream.bufferedReader().readText()
        return Applied(process.waitFor(), output, manifest.takeIf { it.isFile }?.readText())
    }

    private fun tarball(env: Map<String, String> = artifactEnv()): StatefulSet = apply(env).statefulSet()

    private fun image(
        env: Map<String, String> = imageEnv(),
        args: Map<String, String> = emptyMap(),
    ): StatefulSet = apply(env, args).statefulSet()

    private fun StatefulSet.container(name: String): Container =
        (spec.template.spec.initContainers + spec.template.spec.containers).single { it.name == name }

    private fun StatefulSet.cassandra(): Container = container("cassandra")

    private fun StatefulSet.allContainers(): List<Container> = spec.template.spec.initContainers + spec.template.spec.containers

    private fun Container.envValue(name: String): String? = env.single { it.name == name }.value

    @Test
    fun `kit yaml declares a db kit with the ten named args, the contributor, the CQL endpoint and Java agent metrics`() {
        val args = kit.config.args.associateBy { it.flag }

        assertThat(kit.config.type).isEqualTo(KitType.DB)
        assertThat(args.keys).containsExactlyInAnyOrder(
            "--version",
            "--build",
            "--java",
            "--image",
            "--nodes",
            "--heap",
            "--memory",
            "--cpus",
            "--dc",
            "--storage-size",
        )
        assertThat(args.getValue("--version").default).isEqualTo("5.0")
        assertThat(args.getValue("--build").default).isEmpty()
        assertThat(args.getValue("--java").default).isEmpty()
        assertThat(args.getValue("--image").default).isEmpty()
        assertThat(args.getValue("--nodes").default).isEqualTo("\${DB_NODE_COUNT}")
        // Empty: the contributor sizes them for the cluster (design D8).
        assertThat(args.getValue("--memory").default).isEmpty()
        assertThat(args.getValue("--cpus").default).isEmpty()
        assertThat(args.getValue("--dc").default).isEqualTo("dc1")
        assertThat(args.getValue("--storage-size").default).isEqualTo("\${KIT_STORAGE_SIZE}")
        assertThat(kit.config.contributors).containsExactly(Constants.Kit.Contributors.CASSANDRA_ARTIFACT)
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

    /**
     * The docs send a patch edit on a running kit through `upgrade` because `start` refuses one; a
     * kit whose `start` re-applied instead would make that guidance wrong (and a second `start`
     * would race the running pods' claims).
     */
    @Test
    fun `start refuses a running kit, so a patch edit is applied by upgrade`() {
        assertThat(kit.config.collisionCheck.guards(Constants.Kit.PHASE_START)).isTrue()
        assertThat(kit.config.runtime?.selector).isEqualTo("easydblab/kit=cassandra-pods")
    }

    /** An upgrade records what it applied, and clears what it was not given, so the next start runs the same thing. */
    @Test
    fun `upgrade takes --version, --build and --java and records all three`() {
        val upgrade = kit.config.commands.getValue("upgrade")

        assertThat(upgrade.args.map { it.flag }).containsExactlyInAnyOrder("--version", "--build", "--java")
        assertThat(upgrade.args.map { it.default }).containsOnly("")
        assertThat(upgrade.records).containsExactlyInAnyOrder("CASSANDRA_VERSION", "CASSANDRA_BUILD", "CASSANDRA_JAVA")
    }

    @Test
    fun `start builds the pods' ConfigMap before the StatefulSet`() {
        val scripts =
            kit.config.start
                .filterIsInstance<InstallStep.Shell>()
                .map { it.script }

        val configMap = scripts.indexOfFirst { it.contains("lib/apply-config.sh") }
        val statefulSet = scripts.indexOfFirst { it.contains("lib/apply-statefulset.sh") }
        assertThat(configMap).isNotNegative().isLessThan(statefulSet)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `runs --nodes replicas one at a time, one per db node, restarting them one at a time`(tarballPath: Boolean) {
        val args = mapOf("CASSANDRA_NODES" to "3")
        val sts = apply(if (tarballPath) artifactEnv() else imageEnv(), args).statefulSet()
        val spec = sts.spec.template.spec

        assertThat(sts.spec.replicas).isEqualTo(3)
        assertThat(sts.spec.podManagementPolicy).isEqualTo("OrderedReady")
        assertThat(sts.spec.updateStrategy.type).isEqualTo("RollingUpdate")
        assertThat(spec.nodeSelector).containsEntry("type", "db")
        val antiAffinity =
            spec.affinity.podAntiAffinity.requiredDuringSchedulingIgnoredDuringExecution
                .single()
        assertThat(antiAffinity.topologyKey).isEqualTo("kubernetes.io/hostname")
        assertThat(sts.spec.template.metadata.labels).containsAllEntriesOf(antiAffinity.labelSelector.matchLabels)
    }

    @Test
    fun `the tarball path fetches, renders, then runs Cassandra from the cache on the runtime image`() {
        val sts = tarball()
        val runtime = "ghcr.io/rustyrazorblade/cassandra-runtime:1-jre17"
        val home = "/var/lib/cassandra/${Constants.Kit.CassandraPods.ARTIFACT_CACHE_ROOT}/sha256-$sha"

        assertThat(
            sts.spec.template.spec.initContainers
                .map { it.name },
        ).containsExactly("otel-agent", "cassandra-fetch", "cassandra-render")
        listOf("cassandra-fetch", "cassandra-render", "cassandra").forEach { name ->
            assertThat(sts.container(name).image).describedAs(name).isEqualTo(runtime)
            assertThat(sts.container(name).envValue("EDL_CASSANDRA_HOME")).describedAs(name).isEqualTo(home)
        }
        assertThat(sts.container("cassandra-fetch").command).containsExactly("sh", "/edl/config/fetch-tarball.sh")
        assertThat(sts.container("cassandra-render").command).containsExactly("sh", "/edl/config/render-config.sh")
        assertThat(sts.cassandra().command.joinToString(" ")).contains("exec \"\$EDL_CASSANDRA_HOME/bin/cassandra\" -f")
        assertThat(sts.cassandra().envValue("CASSANDRA_HOME")).isEqualTo(home)
    }

    @Test
    fun `the artifact is substituted literally, whatever a presigned URL holds`() {
        val applied = apply(artifactEnv("EDL_TARBALL_FALLBACK_URL" to "https://archive/x.tgz?a=1&b=\\2"))
        val fetch = applied.statefulSet().container("cassandra-fetch")
        val secret = applied.artifactSecret()

        assertThat(secret.stringData).containsEntry("EDL_TARBALL_URL", presigned)
        assertThat(secret.stringData).containsEntry("EDL_TARBALL_FALLBACK_URL", "https://archive/x.tgz?a=1&b=\\2")
        listOf("EDL_TARBALL_URL", "EDL_TARBALL_FALLBACK_URL").forEach { name ->
            assertThat(
                fetch.env
                    .single { it.name == name }
                    .valueFrom.secretKeyRef.name,
            ).describedAs(name).isEqualTo(secret.metadata.name)
        }
        assertThat(fetch.envValue("EDL_TARBALL_SHA256")).isEqualTo(sha)
        assertThat(fetch.envValue("EDL_ARTIFACT_CACHE")).isEqualTo("/var/lib/cassandra/${Constants.Kit.CassandraPods.ARTIFACT_CACHE_ROOT}")
    }

    /**
     * The catalog resolver signs a new URL on every `upgrade`; were the URL in the pod template,
     * every upgrade of a catalog build would roll every pod even with nothing changed.
     */
    @Test
    fun `a re-signed URL leaves the pod template unchanged and reaches the fetch through the artifact Secret`() {
        val resigned = "https://bucket.s3.amazonaws.com/c.tar.gz?X-Amz-Expires=604800&X-Amz-Signature=other"
        val first = apply(artifactEnv())
        val second = apply(artifactEnv("EDL_TARBALL_URL" to resigned))

        assertThat(second.statefulSet().spec.template).isEqualTo(first.statefulSet().spec.template)
        assertThat(first.artifactSecret().stringData).containsEntry("EDL_TARBALL_URL", presigned)
        assertThat(second.artifactSecret().stringData).containsEntry("EDL_TARBALL_URL", resigned)
        assertThat(second.artifactSecret().metadata.labels).containsEntry("easydblab/kit", "cassandra-pods")
    }

    @Test
    fun `the pod template carries the config hash, so only a changed input rolls the pods`() {
        val sts = tarball(artifactEnv("EDL_CONFIG_HASH" to "feedfacecafebeef"))

        assertThat(sts.spec.template.metadata.annotations).containsEntry("easydblab/config-hash", "feedfacecafebeef")
    }

    @Test
    fun `a missing contributed variable fails the apply and applies nothing`() {
        val applied = apply(artifactEnv() - "EDL_CONFIG_HASH")

        assertThat(applied.exitCode).isNotZero()
        assertThat(applied.output).contains("EDL_CONFIG_HASH")
        assertThat(applied.manifest).isNull()
    }

    @Test
    fun `an --image start with no resolved image applies nothing`() {
        val applied = apply(imageEnv(), resolvedImage = null)

        assertThat(applied.exitCode).isNotZero()
        assertThat(applied.output).contains("resolved-image.env")
        assertThat(applied.manifest).isNull()
    }

    @Test
    fun `the tarball path meets Pod Security restricted, with the claim owned by the cassandra user`() {
        val sts = tarball()
        val pod = sts.spec.template.spec.securityContext

        assertThat(pod.runAsNonRoot).isTrue()
        assertThat(pod.runAsUser).isEqualTo(999L)
        assertThat(pod.fsGroup).isEqualTo(999L)
        assertThat(pod.seccompProfile.type).isEqualTo("RuntimeDefault")
        sts.allContainers().forEach { container ->
            assertThat(container.securityContext.allowPrivilegeEscalation).describedAs(container.name).isFalse()
            assertThat(container.securityContext.capabilities.drop).describedAs(container.name).containsExactly("ALL")
            assertThat(container.ports.orEmpty().mapNotNull { it.hostPort }).describedAs(container.name).isEmpty()
        }
        assertThat(
            sts.spec.template.spec.volumes
                .mapNotNull { it.hostPath },
        ).isEmpty()
        assertThat(sts.spec.template.spec).extracting("hostNetwork").isNotEqualTo(true)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `on Guaranteed nodes every container's requests equal its limits`(tarballPath: Boolean) {
        val sts = if (tarballPath) tarball() else image()

        sts.allContainers().forEach { container ->
            val resources = container.resources
            assertThat(resources.requests).describedAs(container.name).isNotEmpty()
            assertThat(resources.limits).describedAs(container.name).isEqualTo(resources.requests)
        }
        assertThat(
            sts
                .cassandra()
                .resources.limits
                .getValue("cpu")
                .toString(),
        ).isEqualTo("7")
    }

    @Test
    fun `elsewhere the cassandra container requests CPU with no CPU limit`() {
        val resources = tarball(artifactEnv("EDL_CASSANDRA_RESOURCES" to burstable, "EDL_INIT_RESOURCES" to "{}")).cassandra().resources

        assertThat(resources.requests.mapValues { it.value.toString() }).isEqualTo(mapOf("cpu" to "7", "memory" to "3Gi"))
        assertThat(resources.limits.mapValues { it.value.toString() }).isEqualTo(mapOf("memory" to "3Gi"))
    }

    @Test
    fun `the init containers and Cassandra read the ConfigMap, and Cassandra reads the rendered conf`() {
        val sts = tarball()
        val volumes =
            sts.spec.template.spec.volumes
                .associateBy { it.name }

        assertThat(volumes.getValue("config").configMap.name).isEqualTo("cassandra-pods-config")
        assertThat(volumes.getValue("conf").emptyDir).isNotNull()
        listOf("cassandra-fetch", "cassandra-render", "cassandra").forEach { name ->
            assertThat(
                sts
                    .container(name)
                    .volumeMounts
                    .single { it.name == "config" }
                    .mountPath,
            ).describedAs(name).isEqualTo("/edl/config")
        }
        assertThat(sts.container("cassandra-render").volumeMounts.single { it.name == "conf" })
            .extracting("readOnly")
            .isNotEqualTo(true)
        assertThat(
            sts
                .cassandra()
                .volumeMounts
                .single { it.name == "conf" }
                .mountPath,
        ).isEqualTo("/edl/conf")
        assertThat(sts.cassandra().envValue("CASSANDRA_CONF")).isEqualTo("/edl/conf")
        assertThat(sts.cassandra().envValue("CASSANDRA_INCLUDE")).isEqualTo("/edl/conf/cassandra.in.sh")
        assertThat(sts.cassandra().envValue("ECL_AGENTS_LIB")).isEqualTo("/edl/config/edl-cassandra-agents.sh")
    }

    /** The packer snippet reads these in place of the VM paths; every signal must still carry the node name. */
    @Test
    fun `the tarball path points the packer snippet at the agent, the rules, the collector and the node name`() {
        val sts = tarball()
        val cassandra = sts.cassandra()
        val agentMount = cassandra.volumeMounts.single { it.mountPath == Constants.Kit.AGENTS_MOUNT }
        val rulesMount = cassandra.volumeMounts.single { it.name == "jmx-rules" }

        assertThat(cassandra.envValue("EDL_OTEL_AGENT_JAR")).isEqualTo("${Constants.Kit.AGENTS_MOUNT}/${JavaAgent.OTEL.fileName}")
        assertThat(cassandra.envValue("EDL_OTEL_JMX_CONFIG")).isEqualTo("${rulesMount.mountPath}/cassandra-jmx-rules.yaml")
        assertThat(cassandra.envValue("EDL_OTLP_ENDPOINT")).isEqualTo("http://otel-collector.default.svc:4318")
        assertThat(cassandra.envValue("EDL_CASSANDRA_BUILD")).isEqualTo("5.0.6")
        assertThat(cassandra.envValue("EDL_OTEL_EXTRA_RESOURCE_ATTRIBUTES")).isEqualTo("host.name=$(NODE_NAME)")
        assertThat(cassandra.env.map { it.name }).containsSubsequence("NODE_NAME", "EDL_OTEL_EXTRA_RESOURCE_ATTRIBUTES")
        assertThat(agentMount.readOnly).isTrue()
        assertThat(sts.container("otel-agent").image).isEqualTo(JavaAgent.OTEL.image)
    }

    @Test
    fun `the --image path keeps the image's entrypoint, with the resolved image and build`() {
        val sts = image()
        val cassandra = sts.cassandra()
        val env = cassandra.env.associateBy { it.name }
        val port = Constants.Kit.CassandraPods.CQL_NODE_PORT

        assertThat(
            sts.spec.template.spec.initContainers
                .map { it.name },
        ).containsExactly("otel-agent")
        assertThat(cassandra.image).isEqualTo("ghcr.io/example/cassandra:sha-abc")
        assertThat(
            env.getValue("OTEL_RESOURCE_ATTRIBUTES").value,
        ).contains("host.name=$(NODE_NAME)", "node_role=db", "cassandra_build=sha-abc")
        assertThat(
            env
                .getValue("HOST_IP")
                .valueFrom.fieldRef.fieldPath,
        ).isEqualTo("status.hostIP")
        assertThat(env.getValue("CASSANDRA_BROADCAST_RPC_ADDRESS").value).isEqualTo("$(HOST_IP)")
        assertThat(cassandra.env.map { it.name }).containsSubsequence("HOST_IP", "CASSANDRA_BROADCAST_RPC_ADDRESS")
        assertThat(cassandra.command.joinToString(" ")).contains("native_transport_port: $port")
        assertThat(env.getValue("CASSANDRA_DC").value).isEqualTo("dc1")
        assertThat(env.getValue("CASSANDRA_RACK").value).isEqualTo(Constants.Kit.CassandraPods.RACK)
        assertThat(env.getValue("CASSANDRA_ENDPOINT_SNITCH").value).isEqualTo("GossipingPropertyFileSnitch")
        assertThat(env.getValue("CASSANDRA_SEEDS").value).isEqualTo(Constants.Kit.CassandraPods.SEED_DNS)
        assertThat(env.getValue("JVM_EXTRA_OPTS").value).contains(JavaAgentDelivery.flag(JavaAgent.OTEL))
        assertThat(env.getValue("OTEL_EXPORTER_OTLP_ENDPOINT").value).isEqualTo("http://otel-collector.default.svc:4318")
    }

    @ParameterizedTest
    @ValueSource(strings = [TARBALL_TEMPLATE, IMAGE_TEMPLATE])
    fun `the agent comes from an init container image, never a host path`(template: String) {
        val sts = if (template == TARBALL_TEMPLATE) tarball() else image()
        val spec = sts.spec.template.spec
        val init = sts.container("otel-agent")
        val agentVolume = spec.volumes.single { it.name == init.volumeMounts.single().name }

        assertThat(init.image).isEqualTo(JavaAgent.OTEL.image)
        assertThat(agentVolume.emptyDir).isNotNull()
        assertThat(spec.volumes.mapNotNull { it.hostPath?.path }).noneMatch { it.startsWith("/usr/local/otel") }
        assertThat(
            sts
                .cassandra()
                .volumeMounts
                .single { it.name == agentVolume.name }
                .mountPath,
        ).isEqualTo(Constants.Kit.AGENTS_MOUNT)
    }

    /**
     * `--dc` accepts letters, digits, `_` and `-`, so a DC can look like a YAML 1.1 int, float, bool
     * or null. Parsed as Kubernetes parses it, the `--image` path's env value must still be that string.
     */
    @ParameterizedTest
    @ValueSource(strings = ["123", "true", "no", "null", "1e3", "0x1F"])
    fun `a DC that looks like another YAML type stays a string env value`(dc: String) {
        val env = renderedCassandraEnv(mapOf("CASSANDRA_DC" to dc))

        assertThat(env.getValue("CASSANDRA_DC")).isEqualTo(dc)
        assertThat(env.getValue("CASSANDRA_CLUSTER_NAME")).isInstanceOf(String::class.java)
    }

    /** The `--image` cassandra container's env as name to parsed value, loaded with YAML 1.1 rules. */
    private fun renderedCassandraEnv(args: Map<String, String>): Map<String, Any?> {
        val rendered = kit.renderText(IMAGE_TEMPLATE, mapOf("CASSANDRA_NODES" to "2") + args)
        val statefulSet =
            Yaml()
                .loadAll(rendered)
                .filterIsInstance<Map<*, *>>()
                .single { it["kind"] == "StatefulSet" }
        val containers = statefulSet.path("spec", "template", "spec")["containers"] as List<*>
        val cassandra = containers.filterIsInstance<Map<*, *>>().single { it["name"] == "cassandra" }
        return (cassandra["env"] as List<*>).filterIsInstance<Map<*, *>>().associate { it["name"].toString() to it["value"] }
    }

    private fun Map<*, *>.path(vararg keys: String): Map<*, *> = keys.fold(this) { node, key -> node[key] as Map<*, *> }

    @Test
    fun `NodePort service maps the CQL port to itself and keeps traffic on the node`() {
        val nodePort = kit.render("nodeport-service.yaml.template").filterIsInstance<Service>().single()
        val port = nodePort.spec.ports.single()

        assertThat(nodePort.spec.type).isEqualTo("NodePort")
        assertThat(nodePort.spec.externalTrafficPolicy).isEqualTo("Local")
        assertThat(listOf(port.port, port.nodePort, port.targetPort.intVal)).containsOnly(Constants.Kit.CassandraPods.CQL_NODE_PORT)
        assertThat(
            tarball()
                .spec.template.metadata.labels,
        ).containsAllEntriesOf(nodePort.spec.selector)
    }

    @Test
    fun `headless service gives stable DNS for the seed, including pods that are not ready`() {
        val headless = kit.render("headless-service.yaml.template").filterIsInstance<Service>().single()
        val sts = tarball()

        assertThat(headless.spec.clusterIP).isEqualTo("None")
        assertThat(headless.spec.publishNotReadyAddresses).isTrue()
        assertThat(sts.spec.serviceName).isEqualTo(headless.metadata.name)
        assertThat(Constants.Kit.CassandraPods.SEED_DNS).startsWith("${sts.metadata.name}-0.${headless.metadata.name}.")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `claims name the kit's own class and no selector`(tarballPath: Boolean) {
        val sts = if (tarballPath) tarball() else image()
        val pvs =
            kit.config.install
                .filterIsInstance<InstallStep.KitStorage>()
                .single()
        val claim = sts.spec.volumeClaimTemplates.single()

        assertThat(sts.metadata.name).isEqualTo("cassandra-pods")
        assertThat(claim.metadata.name).isEqualTo(pvs.volumeClaimTemplateName)
        assertThat(claim.spec.storageClassName).isEqualTo("edl-local-cassandra-pods")
        assertThat(claim.spec.selector).isNull()
        assertThat(
            sts
                .cassandra()
                .volumeMounts
                .single { it.name == claim.metadata.name }
                .mountPath,
        ).isEqualTo("/var/lib/cassandra")
    }

    @Test
    fun `stop deletes by the kit label and never the claims, uninstall deletes the volumes`() {
        val stop =
            kit.config.stop
                .filterIsInstance<InstallStep.Delete>()
                .single { it.bySelector }

        assertThat(stop.selector).isEqualTo("easydblab/kit=cassandra-pods")
        assertThat(stop.kinds).contains("statefulset", "service", "configmap", "secret").doesNotContain("pvc", "persistentvolumeclaim")
        assertThat(kit.config.uninstall.filterIsInstance<InstallStep.KitStorageDelete>()).hasSize(1)
        assertThat(kit.config.runtime?.selector).isEqualTo("easydblab/kit=cassandra-pods")
    }

    /**
     * Runs [command] (a probe or hook's last argument) with a `nodetool` that prints [statusbinary]
     * on both the PATH (the `--image` path) and under `$EDL_CASSANDRA_HOME/bin` (the tarball path).
     */
    private fun runWithNodetool(
        command: String,
        statusbinary: String,
    ): Int {
        val home = File(tempDir, "home-" + (command + statusbinary).hashCode())
        val bin = File(home, "bin").apply { mkdirs() }
        File(bin, "nodetool").apply {
            writeText("#!/bin/sh\necho '$statusbinary'\n")
            setExecutable(true)
        }
        val process =
            ProcessBuilder("sh", "-c", command)
                .apply {
                    environment()["PATH"] = "${bin.absolutePath}:${System.getenv("PATH")}"
                    environment()["EDL_CASSANDRA_HOME"] = home.absolutePath
                }.redirectErrorStream(true)
                .start()
        return process.waitFor()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `the readiness probe passes only while the native transport is running, never on not running`(tarballPath: Boolean) {
        val command =
            (if (tarballPath) tarball() else image())
                .cassandra()
                .readinessProbe.exec.command
                .last()

        assertThat(runWithNodetool(command, "running")).isZero()
        // "not running" contains "running": a substring match would mark a pod with CQL down Ready.
        assertThat(runWithNodetool(command, "not running")).isNotZero()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `the container is given its memory limit in MiB for the default heap`(tarballPath: Boolean) {
        val ref =
            (if (tarballPath) tarball() else image())
                .cassandra()
                .env
                .single { it.name == "MEMORY_LIMIT_MIB" }
                .valueFrom.resourceFieldRef

        assertThat(ref.resource).isEqualTo("limits.memory")
        assertThat(ref.divisor.toString()).isEqualTo("1Mi")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `the cassandra container drains on preStop, bounded inside the termination grace period`(tarballPath: Boolean) {
        val sts = if (tarballPath) tarball() else image()
        val command =
            sts
                .cassandra()
                .lifecycle.preStop.exec.command
                .joinToString(" ")
        val bound =
            Regex("""timeout (\d+) \S*nodetool"? drain""")
                .find(command)
                ?.groupValues
                ?.get(1)
                ?.toInt()

        assertThat(bound).describedAs("preStop: $command").isNotNull()
        assertThat(bound!!).isLessThan(
            sts.spec.template.spec.terminationGracePeriodSeconds
                .toInt(),
        )
    }

    @Test
    fun `the JMX rules are packaged from their single source`() {
        val source =
            CassandraPodsKitTest::class.java.classLoader
                .getResourceAsStream("com/rustyrazorblade/easydblab/configuration/cassandra/cassandra-jmx-rules.yaml")!!
                .readBytes()

        assertThat(kit.resource("cassandra-jmx-rules.yaml").toByteArray()).isEqualTo(source)
    }

    /** The pods merge and start Cassandra with the VM path's own scripts, so the two cannot drift. */
    @ParameterizedTest
    @ValueSource(strings = ["lib/edl-cassandra-config.sh", "lib/edl-cassandra-agents.sh", "cassandra.in.sh"])
    fun `the shared Cassandra scripts are packaged from packer, byte for byte`(source: String) {
        assertThat(kit.resource("config/${source.substringAfterLast('/')}").toByteArray())
            .isEqualTo(File("packer/cassandra/$source").readBytes())
    }

    @Test
    fun `every NodePort-range number in the kit's packaged files is the CQL port constant`() {
        val dir =
            File(
                CassandraPodsKitTest::class.java.classLoader
                    .getResource("com/rustyrazorblade/easydblab/kits/cassandra-pods")!!
                    .toURI(),
            )
        val port =
            Constants.Kit.CassandraPods.CQL_NODE_PORT
                .toString()
        val found =
            dir
                .walkTopDown()
                .filter { it.isFile }
                .flatMap { file -> Regex("""\b309\d\d\b""").findAll(file.readText()).map { file.name to it.value } }
                .toList()

        assertThat(found).isNotEmpty
        assertThat(found.filter { it.second != port }).describedAs("files naming a 309xx port other than $port").isEmpty()
        assertThat(found.map { it.first }).contains("kit.yaml", TARBALL_TEMPLATE, IMAGE_TEMPLATE, "nodeport-service.yaml.template")
    }

    private companion object {
        const val TARBALL_TEMPLATE = "statefulset.yaml.template"
        const val IMAGE_TEMPLATE = "statefulset-image.yaml.template"
    }
}
