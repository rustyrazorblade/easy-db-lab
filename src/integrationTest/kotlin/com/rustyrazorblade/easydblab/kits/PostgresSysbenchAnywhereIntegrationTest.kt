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
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory
import com.rustyrazorblade.easydblab.kubernetes.currentContextOf
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import com.rustyrazorblade.easydblab.services.ClientNetworkProbe
import com.rustyrazorblade.easydblab.services.DefaultHelmService
import com.rustyrazorblade.easydblab.services.DefaultK8sService
import com.rustyrazorblade.easydblab.services.DefaultKitEndpointResolver
import com.rustyrazorblade.easydblab.services.DefaultKubectlService
import com.rustyrazorblade.easydblab.services.ExtensionRegistry
import com.rustyrazorblade.easydblab.services.ExtensionResolver
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
import com.rustyrazorblade.easydblab.services.access.ServiceAccess
import com.rustyrazorblade.easydblab.services.installConfigYaml
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.stubbing.Answer
import org.slf4j.LoggerFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The postgres kit and the sysbench kit on a one-node cluster whose node carries no easy-db-lab
 * label, as on a cluster easy-db-lab did not build. The cluster state records what `init` records
 * for such a cluster: unmapped roles ([RoleSelectors.ANYWHERE]), no `NODE_SSH`, kit claims on a
 * provisioned class that retains, and the client networks [ClientNetworkProbe] reads from the
 * node.
 *
 * Both kits run as they do from the CLI. The postgres templates are rendered with the kit's
 * defaults; its `install:` steps (the CNPG chart) and `start:` steps run through
 * [WorkloadStepExecutor] with the host's helm and kubectl ([LocalClusterTooling]); sysbench's
 * `prepare` and `start` scripts run through [KitProcessEnvironment] against the target endpoint
 * [DefaultKitEndpointResolver] resolves. Every pod must land on the one node, and the sysbench run
 * pod must end Succeeded. Any SSH call fails the test.
 *
 * It starts its own K3s container, because it installs cluster-scoped kinds (the CNPG CRDs and
 * webhooks). It needs Docker, helm and kubectl, and network access to the CNPG chart repository and
 * the image registries. It never skips.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresSysbenchAnywhereIntegrationTest {
    private lateinit var workspace: File
    private lateinit var k3s: K3sContainer
    private lateinit var client: KubernetesClient
    private lateinit var stateManager: ClusterStateManager
    private lateinit var processEnvironment: KitProcessEnvironment

    @BeforeAll
    fun setup(
        @TempDir workspace: File,
    ) {
        this.workspace = workspace
        requireDocker()
        requireOnPath("helm", "kubectl")
        k3s =
            K3sContainer(DockerImageName.parse(SharedK3s.K3S_IMAGE))
                .withPrivilegedMode(true)
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig!!
                        .withCgroupnsMode("host")
                        .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
                }.withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("k3s")))
                .withEnv("K3S_SNAPSHOTTER", "native")
                .let { (it as K3sContainer).withPreloadedImages(LOCAL_PATH_PROVISIONER_IMAGE, LOCAL_PATH_HELPER_IMAGE) }
        k3s.start()
        client = KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        val kubeconfig = File(workspace, Constants.K3s.LOCAL_KUBECONFIG).apply { writeText(k3s.kubeConfigYaml) }
        ProxyEnvFile(workspace).recordDirect(true)
        createRetainedCopyOfLocalPath()

        val api = KubeApi(kubeconfig.toPath(), checkNotNull(currentContextOf(kubeconfig.toPath())))
        stateManager = ClusterStateManager(File(workspace, CLUSTER_STATE))
        stateManager.save(
            ClusterState(
                name = CLUSTER_NAME,
                initConfig = InitConfig(name = CLUSTER_NAME, kitStorageClass = RETAINED_CLASS),
                capabilities = emptySet(),
                roleSelectors = RoleSelectors.ANYWHERE,
                clientNetworks = ClientNetworkProbe(ProxiedKubernetesClientFactory()).probe(api, emptyList()).cidrs.map { it.value },
            ),
        )
        processEnvironment = KitProcessEnvironment(ToolWrapperInstaller(), stateManager)
    }

    @AfterAll
    fun teardown() {
        if (::client.isInitialized) client.close()
        if (::k3s.isInitialized) k3s.stop()
    }

    @Test
    fun `postgres starts and sysbench prepares and runs on one unlabelled node`() {
        val node =
            client
                .nodes()
                .list()
                .items
                .single()
        assertThat(node.metadata.labels).doesNotContainKey(Constants.NODE_TYPE_LABEL)

        val postgresDir = installPostgres()
        val sysbenchDir = installSysbench()
        val targetVars = DefaultKitEndpointResolver(NO_HTTP_ENDPOINT).resolveTargetVars(postgresDir, stateManager.load())
        assertThat(targetVars).containsEntry("TARGET_PG_HOST", "postgres-nodeport.default.svc")

        runScript(sysbenchDir, "prepare", targetVars + PREPARE_ARGS)
        runScript(sysbenchDir, "start", targetVars + START_ARGS)

        val runPod = sysbenchRunPods().single()
        assertThat(runPod.status.phase)
            .describedAs("sysbench run pod\n${clusterDiagnostics(client, NAMESPACE)}")
            .isEqualTo("Succeeded")
        val placements = allPods().associate { "${it.metadata.namespace}/${it.metadata.name}" to it.spec.nodeName }
        assertThat(placements).isNotEmpty
        assertThat(placements.values).describedAs("pod placements $placements").containsOnly(node.metadata.name)
    }

    /** Renders the postgres kit with its defaults, then runs its `install:` and `start:` steps. */
    private fun installPostgres(): File {
        val kitDir = File(workspace, POSTGRES).apply { mkdirs() }
        val config = installConfigYaml.decodeFromString(KitConfig.serializer(), kitResource(POSTGRES, CONFIG))
        File(kitDir, CONFIG).writeText(kitResource(POSTGRES, CONFIG))
        val variables = kitVariables(POSTGRES, config) + postgresImageVars()
        for (template in POSTGRES_TEMPLATES) {
            File(kitDir, template.removeSuffix(TEMPLATE_SUFFIX)).writeText(render(POSTGRES, template, variables))
        }
        val executor = stepExecutor()
        val context = StepExecutionContext(POSTGRES, stateManager.load(), variables, kitDir, workspace)
        withClusterDiagnostics(client, NAMESPACE) {
            executor.execute(config.install, Constants.Kit.PHASE_INSTALL, context).getOrThrow()
            // `kit install` and `postgres start` are separate commands; the webhook the Cluster needs
            // must be serving before `start` applies one.
            client
                .apps()
                .deployments()
                .inNamespace(CNPG_NAMESPACE)
                .withName(CNPG_DEPLOYMENT)
                .waitUntilReady(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            executor.execute(config.start, Constants.Kit.PHASE_START, context).getOrThrow()
        }
        return kitDir
    }

    /** Writes the sysbench kit's `kit.yaml` and its `bin/` scripts, executable, as `kit install` does. */
    private fun installSysbench(): File {
        val kitDir = File(workspace, SYSBENCH).apply { mkdirs() }
        File(kitDir, CONFIG).writeText(kitResource(SYSBENCH, CONFIG))
        for (phase in listOf("prepare", "start")) {
            File(kitDir, "bin/$phase.sh").apply {
                parentFile.mkdirs()
                writeText(kitResource(SYSBENCH, "bin/$phase.sh$TEMPLATE_SUFFIX"))
                setExecutable(true)
            }
        }
        return kitDir
    }

    /** Runs sysbench's `bin/<phase>.sh` from the workspace, as `KitRunnerCommand` does, and requires exit 0. */
    private fun runScript(
        kitDir: File,
        phase: String,
        args: Map<String, String>,
    ) {
        val config = installConfigYaml.decodeFromString(KitConfig.serializer(), File(kitDir, CONFIG).readText())
        val variables =
            kitVariables(SYSBENCH, config) +
                args +
                ("KUBECONFIG" to File(workspace, Constants.K3s.LOCAL_KUBECONFIG).absolutePath)
        // Output goes to a file, not a pipe read to its end, so a script that never ends still
        // reaches the timeout below.
        val log = File(workspace, "$SYSBENCH-$phase.log")
        val builder =
            ProcessBuilder(File(kitDir, "bin/$phase.sh").absolutePath)
                .directory(workspace)
                .redirectErrorStream(true)
                .redirectOutput(log)
        val process = processEnvironment.applyTo(builder, workspace, variables).start()
        val exited = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!exited) process.descendants().forEach { it.destroyForcibly() }.also { process.destroyForcibly() }
        val output = log.readText()
        assertThat(exited && process.exitValue() == 0)
            .describedAs("sysbench $phase failed\n$output\n${clusterDiagnostics(client, NAMESPACE)}")
            .isTrue()
    }

    /** The kit's arg defaults overlaid by the cluster variables, with the role pools read from the API. */
    private fun kitVariables(
        kitName: String,
        config: KitConfig,
    ): Map<String, String> {
        val state = stateManager.load()
        val k8sService = k8sService()
        val base =
            TemplateVariables
                .from(
                    state = state,
                    kitName = kitName,
                    storageSize = STORAGE_SIZE,
                    placement = RoleNodes(k8sService).placement(RoleSelectors.from(state)),
                ).toMap()
        val defaults = config.args.associate { it.variable to it.default }
        val resolved =
            defaults.mapValues { (_, value) -> value.replace(Constants.Kit.SHELL_VAR_PATTERN) { base[it.groupValues[1]] ?: it.value } }
        return resolved + ("STORAGE_SIZE" to STORAGE_SIZE) + base
    }

    /** The image variables `kit install postgres` adds for a kit with no extension. */
    private fun postgresImageVars(): Map<String, String> {
        val extension =
            ExtensionResolver(ExtensionRegistry.fromClasspath(POSTGRES), POSTGRES_VERSION)
                .resolve(extensions = emptyList(), imageOverride = null, additionalPreload = emptyList(), additionalCreate = emptyList())
        return mapOf(
            "IMAGE" to extension.image,
            "SHARED_PRELOAD_LIBRARIES" to "[]",
            "POST_INIT_SQL" to "[]",
            "PG_MAJOR_VERSION" to POSTGRES_VERSION,
            "POSTGRES_UID" to extension.postgresUid.toString(),
            "POSTGRES_GID" to extension.postgresGid.toString(),
            "POSTGRES_PORT" to extension.postgresPort.toString(),
            "METRICS_PORT" to extension.metricsPort.toString(),
        )
    }

    private fun render(
        kitName: String,
        template: String,
        variables: Map<String, String>,
    ): String {
        val unresolved = mutableListOf<String>()
        val state = stateManager.load()
        val rendered =
            TemplateService(stateManager).renderKitTemplate(
                templateContent = kitResource(kitName, template),
                vars = TemplateVariables.from(state = state, kitName = kitName, storageSize = STORAGE_SIZE),
                extraVars = variables,
            ) { unresolved.addAll(it) }
        check(unresolved.isEmpty()) { "Unresolved variables in $kitName/$template: $unresolved" }
        return rendered
    }

    /** The production step executor with helm and kubectl run locally; any SSH call throws. */
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

    private fun sysbenchRunPods(): List<Pod> =
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withLabel("easydblab/kit", SYSBENCH)
            .withLabel("easydblab/role", "sysbench-run")
            .list()
            .items

    private fun allPods(): List<Pod> =
        listOf(NAMESPACE, CNPG_NAMESPACE).flatMap {
            client
                .pods()
                .inNamespace(it)
                .list()
                .items
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

    private fun kitResource(
        kitName: String,
        file: String,
    ): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("$KIT_RESOURCES/$kitName/$file")) {
            "$file not found in built-in kit $kitName"
        }.bufferedReader().use { it.readText() }

    private fun requireDocker() {
        check(DockerClientFactory.instance().isDockerAvailable) {
            "Docker is not available, so the K3s container this test needs cannot start. Start (or restart) Docker and run it again."
        }
    }

    private fun requireOnPath(vararg tools: String) {
        val directories =
            System
                .getenv("PATH")
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotEmpty() }
        val missing = tools.filter { tool -> directories.none { File(it, tool).let { file -> file.isFile && file.canExecute() } } }
        check(missing.isEmpty()) {
            "${missing.joinToString()} not found on PATH. The kits run the host's helm and kubectl, so this test needs both installed."
        }
    }

    private companion object {
        const val CLUSTER_NAME = "postgres-sysbench-anywhere"
        const val NAMESPACE = "default"
        const val CNPG_NAMESPACE = "cnpg-system"

        /** The Deployment the kit's `cnpg-operator` release of `cnpg/cloudnative-pg` creates. */
        const val CNPG_DEPLOYMENT = "cnpg-operator-cloudnative-pg"
        const val POSTGRES = "postgres"
        const val POSTGRES_VERSION = "17"
        const val SYSBENCH = "sysbench"
        const val CONFIG = Constants.Kit.CONFIG_FILE
        const val TEMPLATE_SUFFIX = ".template"
        val POSTGRES_TEMPLATES = listOf("postgres-cluster.yaml.template", "nodeport-service.yaml.template")
        const val KIT_RESOURCES = "com/rustyrazorblade/easydblab/kits"
        const val STORAGE_SIZE = "1Gi"
        const val LOCAL_PATH = "local-path"

        /** postgres declares no HTTP endpoint, so the resolver never opens a Service from this machine. */
        val NO_HTTP_ENDPOINT = ServiceAccess { target -> error("postgres has no HTTP endpoint to open, yet ${target.service} was opened") }
        const val RETAINED_CLASS = "local-path-retain"
        const val LOCAL_PATH_PROVISIONER_IMAGE = "rancher/local-path-provisioner:v0.0.34"
        const val LOCAL_PATH_HELPER_IMAGE = "rancher/mirrored-library-busybox:1.37.0"
        const val TIMEOUT_SECONDS = 600L

        /** A small data set, so the run is quick even where the amd64-only sysbench image is emulated. */
        val PREPARE_ARGS = mapOf("WORKLOAD" to "oltp_read_write", "SCALE" to "1", "TABLES" to "1")
        val START_ARGS =
            mapOf(
                "WORKLOAD" to "oltp_read_write",
                "THREADS" to "2",
                "DURATION" to "10",
                "RATE" to "0",
                "SKIP_TRX" to "off",
                "RAND_TYPE" to "special",
            )
    }
}
