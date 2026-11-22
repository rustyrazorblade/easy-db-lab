package com.rustyrazorblade.easydblab.kits

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.K3sDiagnostics.clusterDiagnostics
import com.rustyrazorblade.easydblab.K3sDiagnostics.withClusterDiagnostics
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.CLUSTER_STATE
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NetworkMode
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.configuration.agents.JavaAgent
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import com.rustyrazorblade.easydblab.services.DefaultHelmService
import com.rustyrazorblade.easydblab.services.DefaultK8sService
import com.rustyrazorblade.easydblab.services.DefaultKubectlService
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.K8sClientProvider
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitProcessEnvironment
import com.rustyrazorblade.easydblab.services.KitStorageProvisioner
import com.rustyrazorblade.easydblab.services.LocalClusterTooling
import com.rustyrazorblade.easydblab.services.RoleNodes
import com.rustyrazorblade.easydblab.services.StepExecutionContext
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.TemplateVariables
import com.rustyrazorblade.easydblab.services.WorkloadStepExecutor
import com.rustyrazorblade.easydblab.services.WorkspaceKubeApiLocator
import com.rustyrazorblade.easydblab.services.aws.S3Uri
import com.rustyrazorblade.easydblab.services.cassandraversions.AsfReleases
import com.rustyrazorblade.easydblab.services.cassandraversions.Cached
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraArtifactContributor
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraBuild
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraBuildsManifest
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraTarballResolver
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraVersionResolver
import com.rustyrazorblade.easydblab.services.cassandraversions.CatalogBuild
import com.rustyrazorblade.easydblab.services.cassandraversions.ProfileBuild
import com.rustyrazorblade.easydblab.services.cassandraversions.PublicHttp
import com.rustyrazorblade.easydblab.services.cassandraversions.TarballDigests
import com.rustyrazorblade.easydblab.services.cassandraversions.VersionSources
import com.rustyrazorblade.easydblab.services.installConfigYaml
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.dsl.ExecListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.stubbing.Answer
import org.slf4j.LoggerFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The `cassandra-pods` kit's tarball path on a one-node K3s cluster, run as the CLI runs it: the
 * real `cassandra-artifact` contributor resolves a real Apache release (its sha256 from the ASF
 * CDN), its files are written into the kit directory, and the kit's own `start:` steps run through
 * [WorkloadStepExecutor] with the host's kubectl, against the `cassandra-runtime` image built from
 * its Dockerfile. The kit's namespace enforces Pod Security "restricted".
 *
 * The tests share one kit and run in order:
 * 1. start reaches UN and answers CQL on the NodePort;
 * 2. a restart with the same sha256 and an unreachable URL still reaches UN (the cache);
 * 3. `upgrade` with the running version applies a `cassandra.patch.yaml` edit (the config hash
 *    rolls the pod) and, run again with nothing changed, rolls nothing;
 * 4. `upgrade --version` to another release rolls the pod onto its tarball and build label.
 *
 * A row written and flushed at the first start, and the node's host ID, must survive every restart
 * and every upgrade: the data lives on the claim, never in the per-sha256 cached tree.
 *
 * Only the cassandra-builds nightly list and the build catalog are left out of [VersionSources]:
 * `--version` of an ASF release never reads them. It needs Docker, kubectl, and the Apache CDN.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CassandraPodsTarballIntegrationTest {
    private lateinit var workspace: File
    private lateinit var kitDir: File
    private lateinit var k3s: K3sContainer
    private lateinit var client: KubernetesClient
    private lateinit var stateManager: ClusterStateManager
    private lateinit var processEnvironment: KitProcessEnvironment
    private lateinit var config: KitConfig

    /** The node's host ID from the first start; every later test expects the same node back. */
    private lateinit var hostId: String

    @BeforeAll
    fun setup(
        @TempDir workspace: File,
    ) {
        this.workspace = workspace
        check(DockerClientFactory.instance().isDockerAvailable) { "Docker is not available; start (or restart) Docker and run again." }
        val runtimeImage = CassandraRuntimeTestImage.build(JAVA)
        k3s =
            K3sContainer(DockerImageName.parse(SharedK3s.K3S_IMAGE))
                .withPrivilegedMode(true)
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig!!
                        .withCgroupnsMode("host")
                        .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
                }.withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("k3s")))
                .withEnv("K3S_SNAPSHOTTER", "native")
                .let {
                    (it as K3sContainer).withPreloadedImages(
                        runtimeImage,
                        JavaAgent.OTEL.image,
                        LOCAL_PATH_PROVISIONER_IMAGE,
                        LOCAL_PATH_HELPER_IMAGE,
                    )
                }
        k3s.start()
        client = KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        File(workspace, Constants.K3s.LOCAL_KUBECONFIG).writeText(k3s.kubeConfigYaml)
        ProxyEnvFile(workspace).recordNetworkMode(NetworkMode.Direct)
        enforceRestrictedPodSecurity()
        createRetainedCopyOfLocalPath()

        stateManager = ClusterStateManager(File(workspace, CLUSTER_STATE))
        stateManager.replace(
            ClusterState(
                name = CLUSTER_NAME,
                initConfig = InitConfig(name = CLUSTER_NAME, kitStorageClass = RETAINED_CLASS),
                capabilities = emptySet(),
                roleSelectors = RoleSelectors.ANYWHERE,
            ),
        )
        processEnvironment = KitProcessEnvironment(ToolWrapperInstaller(), stateManager)
        kitDir = install()
        config = installConfigYaml.decodeFromString(KitConfig.serializer(), File(kitDir, Constants.Kit.CONFIG_FILE).readText())
    }

    @AfterAll
    fun teardown() {
        if (::client.isInitialized) client.close()
        if (::k3s.isInitialized) k3s.stop()
    }

    @Test
    @Order(1)
    fun `start runs the release tarball under restricted Pod Security, reaches UN and answers CQL on the NodePort`() {
        val variables = start(mapOf("CASSANDRA_VERSION" to FIRST_RELEASE))

        val cassandra = statefulSet().cassandraEnv()
        assertThat(cassandra["EDL_CASSANDRA_BUILD"]).isEqualTo(FIRST_RELEASE)
        assertThat(cassandra["EDL_TARBALL_SHA256"]).isEqualTo(variables.getValue("EDL_TARBALL_SHA256"))
        assertThat(
            pod()
                .spec.containers
                .single { it.name == "cassandra" }
                .image,
        ).isEqualTo(CassandraRuntimeTestImage.tag(JAVA))
        assertThat(cql("SELECT release_version FROM system.local")).contains(FIRST_RELEASE)
        assertThat(exec("sh", "-c", "echo \"\$JVM_EXTRA_OPTS\"; cat /proc/1/cmdline | tr '\\0' ' '"))
            .describedAs("the packer snippet's agent options, with the build label")
            .contains("-javaagent:/edl/agents/opentelemetry-javaagent.jar")
            .contains("cassandra_build=$FIRST_RELEASE")
            .contains("host.name=")

        cql(
            "CREATE KEYSPACE $KEYSPACE WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}; " +
                "CREATE TABLE $KEYSPACE.kept (id int PRIMARY KEY, value text); " +
                "INSERT INTO $KEYSPACE.kept (id, value) VALUES (1, '$KEPT_VALUE');",
        )
        // Flushed, so the row is read back from an sstable in data_file_directories, not only replayed.
        exec("sh", "-c", "\"\$EDL_CASSANDRA_HOME/bin/nodetool\" flush $KEYSPACE")
        hostId = hostId()
        assertThat(keptRow()).contains(KEPT_VALUE)
    }

    @Test
    @Order(2)
    fun `a restart with the same sha256 reaches UN with an unreachable URL, so the claim's cached tree is used`() {
        runSteps(config.stop, Constants.Kit.PHASE_STOP, emptyMap())
        waitForPodGone()

        start(
            mapOf("CASSANDRA_VERSION" to FIRST_RELEASE),
            override = mapOf("EDL_TARBALL_URL" to UNREACHABLE, "EDL_TARBALL_FALLBACK_URL" to UNREACHABLE),
        )

        assertThat(artifactUrls()["EDL_TARBALL_URL"]).isEqualTo(UNREACHABLE)
        assertThat(log("cassandra-fetch")).contains("is cached at")
        assertThat(cql("SELECT release_version FROM system.local")).contains(FIRST_RELEASE)
        assertThat(keptRow()).describedAs("the row written before the stop").contains(KEPT_VALUE)
        assertThat(hostId()).describedAs("the node's identity on its claim").isEqualTo(hostId)
    }

    /** `start` refuses a running kit, so `upgrade` with the running version is what applies a patch edit. */
    @Test
    @Order(3)
    fun `upgrade with the running version and an edited patch rolls the pod onto the edit, and with no edit rolls nothing`() {
        val sameVersion = mapOf("CASSANDRA_VERSION" to FIRST_RELEASE, "CASSANDRA_BUILD" to "", "CASSANDRA_JAVA" to "")
        val before =
            statefulSet()
                .spec.template.metadata.annotations[CONFIG_HASH]
        val podBefore = pod().metadata.uid

        File(workspace, Constants.ConfigPaths.CASSANDRA_PATCH_FILE).writeText(
            "cluster_name: \"$CLUSTER_NAME\"\nnum_tokens: 4\nconcurrent_reads: $EDITED_CONCURRENT_READS\n",
        )
        upgrade(sameVersion)

        val edited =
            statefulSet()
                .spec.template.metadata.annotations[CONFIG_HASH]
        assertThat(edited).isNotEqualTo(before)
        assertThat(pod().metadata.uid).describedAs("the pod restarted").isNotEqualTo(podBefore)
        assertThat(exec("sh", "-c", "grep '^concurrent_reads:' /edl/conf/cassandra.yaml"))
            .contains("$EDITED_CONCURRENT_READS")
        assertThat(keptRow()).contains(KEPT_VALUE)
        assertThat(hostId()).isEqualTo(hostId)

        val podAfterEdit = pod().metadata.uid
        upgrade(sameVersion)

        assertThat(
            statefulSet()
                .spec.template.metadata.annotations[CONFIG_HASH],
        ).isEqualTo(edited)
        assertThat(pod().metadata.uid).describedAs("nothing changed, so the pod did not restart").isEqualTo(podAfterEdit)
    }

    @Test
    @Order(4)
    fun `upgrade --version rolls the pod onto the new release's tarball and build label`() {
        val before = statefulSet()

        val variables = upgrade(mapOf("CASSANDRA_VERSION" to SECOND_RELEASE, "CASSANDRA_BUILD" to "", "CASSANDRA_JAVA" to ""))

        val after = statefulSet()
        assertThat(after.cassandraEnv()["EDL_TARBALL_SHA256"])
            .isEqualTo(variables.getValue("EDL_TARBALL_SHA256"))
            .isNotEqualTo(before.cassandraEnv()["EDL_TARBALL_SHA256"])
        assertThat(after.cassandraEnv()["EDL_CASSANDRA_BUILD"]).isEqualTo(SECOND_RELEASE)
        assertThat(after.spec.template.metadata.annotations[CONFIG_HASH])
            .isNotEqualTo(before.spec.template.metadata.annotations[CONFIG_HASH])
        assertThat(exec("sh", "-c", "cat /proc/1/cmdline | tr '\\0' ' '")).contains("cassandra_build=$SECOND_RELEASE")
        assertThat(cql("SELECT release_version FROM system.local")).contains(SECOND_RELEASE)
        assertThat(keptRow()).describedAs("the row written on $FIRST_RELEASE").contains(KEPT_VALUE)
        assertThat(hostId()).describedAs("the same node, on its own claim").isEqualTo(hostId)
    }

    private fun keptRow(): String = cql("SELECT value FROM $KEYSPACE.kept WHERE id = 1")

    /** The node's host ID, from `system.local`: a node that came back on an empty data directory gets a new one. */
    private fun hostId(): String =
        checkNotNull(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").find(cql("SELECT host_id FROM system.local"))) {
            "no host_id in system.local"
        }.value

    /** Renders the kit into the workspace as `kit install cassandra-pods` does, with `--nodes 1`. */
    private fun install(): File {
        val dir = File(workspace, KIT).apply { mkdirs() }
        val fixture = BuiltinKitFixture(KIT, TemplateService(stateManager), kitStorageClass = RETAINED_CLASS)
        File(dir, Constants.Kit.CONFIG_FILE).writeText(fixture.resource(Constants.Kit.CONFIG_FILE))
        val installVars = mapOf("CASSANDRA_NODES" to "1", "STORAGE_SIZE" to STORAGE_SIZE)
        for (file in KIT_FILES) {
            val target = File(dir, file.removeSuffix(TEMPLATE_SUFFIX)).apply { parentFile.mkdirs() }
            val content = if (file.endsWith(TEMPLATE_SUFFIX)) render(fixture.resource(file), installVars) else fixture.resource(file)
            target.writeText(content)
            if (file.startsWith("bin/")) target.setExecutable(true)
        }
        return dir
    }

    private fun render(
        template: String,
        installVars: Map<String, String>,
    ): String {
        val unresolved = mutableListOf<String>()
        val rendered =
            TemplateService(stateManager).renderKitTemplate(template, clusterVariables(), defaults() + installVars) {
                unresolved.addAll(it)
            }
        check(unresolved.isEmpty()) { "Unresolved variables: $unresolved" }
        return rendered
    }

    /** Runs the contributor, writes its files, then the kit's `install:` (first time) and `start:` steps. */
    private fun start(
        args: Map<String, String>,
        override: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val variables = contribute(args) + override
        if (!File(kitDir, ".installed").exists()) {
            runSteps(config.install, Constants.Kit.PHASE_INSTALL, variables)
            File(kitDir, ".installed").writeText("")
        }
        runSteps(config.start, Constants.Kit.PHASE_START, variables)
        return variables
    }

    /** Runs the contributor, then `bin/upgrade.sh` from the workspace, as the runner runs a declared command. */
    private fun upgrade(args: Map<String, String>): Map<String, String> {
        val variables = contribute(args)
        val log = File(workspace, "upgrade.log")
        val builder =
            ProcessBuilder(File(kitDir, "bin/upgrade.sh").absolutePath)
                .directory(workspace)
                .redirectErrorStream(true)
                .redirectOutput(log)
        val process = processEnvironment.applyTo(builder, workspace, variables).start()
        val exited = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!exited) process.destroyForcibly()
        assertThat(exited && process.exitValue() == 0)
            .describedAs("upgrade failed\n${log.readText()}\n${clusterDiagnostics(client, NAMESPACE)}")
            .isTrue()
        return variables
    }

    /**
     * The environment the kit runner gives a step: the kit's arg defaults, the install args, the
     * given [args], the contributor's variables (its files written into the kit directory), the
     * cluster variables and the absolute kubeconfig.
     */
    private fun contribute(args: Map<String, String>): Map<String, String> {
        val state = stateManager.load()
        val resolved = defaults() + mapOf("CASSANDRA_NODES" to "1", "STORAGE_SIZE" to STORAGE_SIZE) + args
        val contribution = contributor().contribute(resolved, state, kitDir)
        contribution.files.forEach { (path, content) -> File(kitDir, path).apply { parentFile.mkdirs() }.writeText(content) }
        return resolved + contribution.variables + templateVariables() +
            ("KUBECONFIG" to File(workspace, Constants.K3s.LOCAL_KUBECONFIG).absolutePath) +
            ("CASSANDRA_UN_WAIT_SECONDS" to "$UN_TIMEOUT_SECONDS")
    }

    private fun contributor(): CassandraArtifactContributor {
        val sources = AsfOnlySources(File(workspace, "profile-builds"))
        return CassandraArtifactContributor(
            CassandraTarballResolver(CassandraVersionResolver(sources), sources, TarballDigests(PublicHttp(), EventBus())),
            RoleNodes(k8sService()),
            EventBus(),
        )
    }

    private fun clusterVariables(): TemplateVariables {
        val state = stateManager.load()
        return TemplateVariables.from(
            state = state,
            kitName = KIT,
            storageSize = STORAGE_SIZE,
            placement = RoleNodes(k8sService()).placement(state),
        )
    }

    private fun templateVariables(): Map<String, String> = clusterVariables().toMap()

    private fun defaults(): Map<String, String> {
        val vars = templateVariables()
        val kitConfig =
            installConfigYaml.decodeFromString(
                KitConfig.serializer(),
                BuiltinKitFixture(KIT, TemplateService(stateManager)).resource(Constants.Kit.CONFIG_FILE),
            )
        return kitConfig.args.associate { arg ->
            arg.variable to arg.default.replace(Constants.Kit.SHELL_VAR_PATTERN) { vars[it.groupValues[1]] ?: it.value }
        }
    }

    private fun runSteps(
        steps: List<InstallStep>,
        phase: String,
        variables: Map<String, String>,
    ) {
        val context = StepExecutionContext(KIT, stateManager.load(), variables, kitDir, workspace)
        withClusterDiagnostics(client, NAMESPACE) { stepExecutor().execute(steps, phase, context).getOrThrow() }
    }

    /** The production step executor with kubectl run locally; any SSH call throws. */
    private fun stepExecutor(): WorkloadStepExecutor {
        val eventBus = EventBus()
        val tooling = LocalClusterTooling(workspace, processEnvironment)
        val k8sService = k8sService()
        val remoteOps = mock<RemoteOperationsService>(defaultAnswer = Answer<Any> { throw AssertionError("SSH used: ${it.method.name}") })
        return WorkloadStepExecutor(
            k8sService = k8sService,
            helmService = DefaultHelmService(tooling),
            kubectlService = DefaultKubectlService(tooling),
            eventBus = eventBus,
            kitProcessEnvironment = processEnvironment,
            kitStorageProvisioner = KitStorageProvisioner(k8sService, remoteOps, eventBus),
        )
    }

    private fun k8sService() =
        DefaultK8sService(K8sClientProvider(WorkspaceKubeApiLocator(stateManager, workspace), ProxiedKubernetesClientFactory()), EventBus())

    private fun statefulSet(): StatefulSet =
        client
            .apps()
            .statefulSets()
            .inNamespace(NAMESPACE)
            .withName(KIT)
            .get()

    private fun StatefulSet.cassandraEnv(): Map<String, String?> =
        spec.template.spec.containers
            .single { it.name == "cassandra" }
            .env
            .associate { it.name to it.value }

    /** The tarball URLs the fetch reads, from the artifact Secret (base64 in `data` once applied). */
    private fun artifactUrls(): Map<String, String> =
        client
            .secrets()
            .inNamespace(NAMESPACE)
            .withName(ARTIFACT_SECRET)
            .get()
            .data
            .mapValues { String(Base64.getDecoder().decode(it.value)) }

    private fun pod(): Pod =
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(POD)
            .get()

    private fun waitForPodGone() {
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(POD)
            .waitUntilCondition({ it == null }, TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun log(container: String): String =
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(POD)
            .inContainer(container)
            .log

    /** cqlsh from the cached tree, against the node IP and NodePort value, as an outside client dials. */
    private fun cql(statement: String): String =
        exec(
            "sh",
            "-c",
            "\"\$EDL_CASSANDRA_HOME/bin/cqlsh\" ${pod().status.hostIP} ${Constants.Kit.CassandraPods.CQL_NODE_PORT} -e \"$statement\"",
        )

    private fun exec(vararg command: String): String {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val done = CompletableFuture<Int>()
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(POD)
            .inContainer("cassandra")
            .writingOutput(out)
            .writingError(err)
            .usingListener(
                object : ExecListener {
                    override fun onClose(
                        code: Int,
                        reason: String?,
                    ) {
                        done.complete(code)
                    }

                    override fun onFailure(
                        t: Throwable?,
                        response: ExecListener.Response?,
                    ) {
                        done.completeExceptionally(t)
                    }
                },
            ).exec(*command)
            .use { watch ->
                done.get(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val status = watch.exitCode().get(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertThat(status).describedAs("exit of ${command.toList()}: $err").isEqualTo(0)
            }
        return out.toString()
    }

    /** The kit's namespace admits only pods that meet Pod Security "restricted". */
    private fun enforceRestrictedPodSecurity() {
        client
            .namespaces()
            .resource(
                NamespaceBuilder()
                    .withNewMetadata()
                    .withName(NAMESPACE)
                    .addToLabels("pod-security.kubernetes.io/enforce", "restricted")
                    .endMetadata()
                    .build(),
            ).serverSideApply()
    }

    /** K3s's `local-path` reclaims with Delete; kit storage must retain, so the kit claims a copy that does. */
    private fun createRetainedCopyOfLocalPath() {
        val localPath =
            client
                .storage()
                .v1()
                .storageClasses()
                .withName(LOCAL_PATH)
                .get()
        client
            .storage()
            .v1()
            .storageClasses()
            .resource(
                StorageClassBuilder()
                    .withNewMetadata()
                    .withName(RETAINED_CLASS)
                    .endMetadata()
                    .withProvisioner(localPath.provisioner)
                    .withVolumeBindingMode(localPath.volumeBindingMode)
                    .withReclaimPolicy("Retain")
                    .build(),
            ).create()
    }

    /**
     * The Apache release list, read live, and nothing else: an ASF `--version` never reaches the
     * cassandra-builds list or the build catalog, so reading either fails the test.
     */
    private class AsfOnlySources(
        override val profileBuildDir: File,
    ) : VersionSources {
        private val http = PublicHttp()

        override fun profileBuild(name: String): ProfileBuild? = null

        override fun profileBuilds(): List<ProfileBuild> = emptyList()

        override fun asfReleases(refresh: Boolean): Cached<AsfReleases> =
            Cached(AsfReleases.parseIndex(checkNotNull(http.getOrNull(AsfReleases.indexUrl()))), Instant.now(), stale = false)

        override fun cassandraBuilds(refresh: Boolean): Cached<List<CassandraBuild>> = error("an ASF release read cassandra-builds")

        override fun cassandraBuildsManifest(build: CassandraBuild): CassandraBuildsManifest? =
            error("an ASF release read cassandra-builds")

        override fun catalogBuild(name: String): CatalogBuild? = error("an ASF release read the build catalog")

        override fun catalogBuildNames(): List<String> = emptyList()

        override fun downloadCacheRoot(): ClusterStoragePath = ClusterStoragePath.downloadCacheRoot("easy-db-lab-test")

        override fun uriOf(path: ClusterStoragePath): String = S3Uri.uri(path)
    }

    private companion object {
        const val KIT = "cassandra-pods"
        const val POD = "cassandra-pods-0"
        const val NAMESPACE = "default"
        const val CLUSTER_NAME = "cassandra-pods-tarball"
        const val JAVA = "17"

        /** Two 5.0 releases the Apache CDN serves; the first is upgraded to the second. */
        const val FIRST_RELEASE = "5.0.8"
        const val SECOND_RELEASE = "5.0.9"
        const val KEYSPACE = "edl_kept"
        const val KEPT_VALUE = "written-before-restart"
        const val CONFIG_HASH = "easydblab/config-hash"
        const val ARTIFACT_SECRET = "cassandra-pods-artifact"
        const val EDITED_CONCURRENT_READS = 96
        const val UNREACHABLE = "https://unreachable.invalid/apache-cassandra.tar.gz"
        const val STORAGE_SIZE = "2Gi"
        const val TEMPLATE_SUFFIX = ".template"
        const val LOCAL_PATH = "local-path"
        const val RETAINED_CLASS = "local-path-retain"
        const val LOCAL_PATH_PROVISIONER_IMAGE = "rancher/local-path-provisioner:v0.0.34"
        const val LOCAL_PATH_HELPER_IMAGE = "rancher/mirrored-library-busybox:1.37.0"
        const val TIMEOUT_SECONDS = 900L
        const val UN_TIMEOUT_SECONDS = 600L
        const val EXEC_TIMEOUT_SECONDS = 120L

        /** Every file `kit install` writes for the tarball path, by its resource name. */
        val KIT_FILES =
            listOf(
                "statefulset.yaml.template",
                "statefulset-image.yaml.template",
                "headless-service.yaml.template",
                "nodeport-service.yaml.template",
                "cassandra-jmx-rules.yaml",
                "lib/check-start.sh",
                "lib/apply-config.sh",
                "lib/apply-statefulset.sh",
                "lib/wait-un.sh",
                "bin/upgrade.sh",
                "config/fetch-tarball.sh",
                "config/render-config.sh",
                "config/edl-cassandra-config.sh",
                "config/edl-cassandra-agents.sh",
                "config/cassandra.in.sh",
            )
    }
}
