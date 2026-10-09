package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.commands.kit.KitInfo
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.CollisionCheck
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.KitMetrics
import com.rustyrazorblade.easydblab.services.KitType
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.Service
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Checks the built-in FerrosaDB kit (ferrosa-kit spec): it runs the `start` shell steps against a
 * stub `kubectl` and parses what they would apply with fabric8 — one Deployment, PVC and Service
 * for each db host, the ring settings, image selection, storage modes and heap profiling — plus
 * every validation gate, the readiness wait's failure reports, and the kit's cross-file contracts.
 */
class FerrosaKitTest : BaseKoinTest() {
    private val kit by lazy {
        BuiltinKitFixture("ferrosa", TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
    }

    private var runs = 0

    /** The `start` shell step that waits for readiness and checks heap profiling. */
    private val readinessStep: String get() =
        kit.config.start
            .filterIsInstance<InstallStep.Shell>()[1]
            .script

    private fun newStub(): StubKubectl = StubKubectl(File(tempDir, "stub-${runs++}"))

    private fun runApply(
        dbNodes: Int,
        args: Map<String, String> = emptyMap(),
    ): FerrosaApplyRun = FerrosaApplyRun(kit, File(tempDir, "stub-${runs++}"), dbNodes, CLUSTER_ENV + args)

    @Test
    fun `is a collision-checked db kit whose runtime selects its pods`() {
        assertThat(kit.config.type).isEqualTo(KitType.DB)
        assertThat(kit.config.collisionCheck).isEqualTo(CollisionCheck.ENABLED)
        assertThat(kit.config.runtime?.selector).isEqualTo(KIT_SELECTOR)
    }

    @Nested
    inner class Topology {
        @Test
        fun `one Deployment, claim and Service for each db host`() {
            val run = runApply(3)

            assertThat(run.exit).describedAs(run.stub.output()).isZero()
            assertThat(run.deployments.map { it.metadata.name }).containsExactly("ferrosa-0", "ferrosa-1", "ferrosa-2")
            assertThat(run.deployments).allSatisfy { deployment ->
                assertThat(deployment.spec.replicas).isEqualTo(1)
                assertThat(deployment.spec.strategy.type).isEqualTo("Recreate")
            }
            assertThat(run.claims.map { it.metadata.name to it.spec.volumeName })
                .containsExactlyInAnyOrder(
                    "ferrosa-data-0" to "data-ferrosa-0",
                    "ferrosa-data-1" to "data-ferrosa-1",
                    "ferrosa-data-2" to "data-ferrosa-2",
                )
            assertThat(run.services.map { it.metadata.name }).containsExactlyInAnyOrder("ferrosa-0", "ferrosa-1", "ferrosa-2")
        }

        @Test
        fun `pod i mounts the claim bound to db host i's platform PV`() {
            val run = runApply(3)
            val pvs =
                kit.config.install
                    .filterIsInstance<InstallStep.PlatformPvs>()
                    .single()

            assertThat(pvs.nodeType).isEqualTo("db")
            for (i in 0..2) {
                val pod =
                    run.deployments
                        .single { it.metadata.name == "ferrosa-$i" }
                        .spec.template.spec
                val claim =
                    pod.volumes
                        .single { it.name == "data" }
                        .persistentVolumeClaim.claimName
                assertThat(claim).isEqualTo("ferrosa-data-$i")
                assertThat(
                    run.claims
                        .single { it.metadata.name == claim }
                        .spec.volumeName,
                ).isEqualTo("${pvs.volumeClaimTemplateName}-ferrosa-$i")
                assertThat(
                    run
                        .container(i)
                        .volumeMounts
                        .single { it.name == "data" }
                        .mountPath,
                ).isEqualTo(DATA_DIR)
            }
        }

        @Test
        fun `pods run only on db nodes, with no host networking and the kit labels`() {
            val run = runApply(3)

            assertThat(run.deployments).allSatisfy { deployment ->
                val pod = deployment.spec.template.spec
                val terms = pod.affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms
                assertThat(terms.flatMap { it.matchExpressions }).anySatisfy { expr ->
                    assertThat(expr.key).isEqualTo("type")
                    assertThat(expr.operator).isEqualTo("In")
                    assertThat(expr.values).containsExactly("db")
                }
                // extracting() reads the unset field without Kotlin's null check on the platform type.
                assertThat(pod).extracting("hostNetwork").isNotEqualTo(true)
                assertThat((pod.containers + pod.initContainers).flatMap { it.ports.orEmpty() }).allSatisfy {
                    assertThat(it).extracting("hostPort").isNull()
                }
            }
            for (i in 0..2) {
                assertThat(
                    run.deployments[i]
                        .spec.template.metadata.labels,
                ).containsAllEntriesOf(
                    mapOf(
                        "easydblab/kit" to "ferrosa",
                        "app.kubernetes.io/name" to "ferrosa",
                        "app.kubernetes.io/instance" to "ferrosa",
                        "easydblab/ferrosa-ordinal" to "$i",
                    ),
                )
            }
        }

        @Test
        fun `every container pulls the image on each start, with no pull secret, and the drain is not cut short`() {
            val run = runApply(3)

            assertThat(run.deployments).allSatisfy { deployment ->
                val pod = deployment.spec.template.spec
                assertThat(pod.imagePullSecrets).isEmpty()
                assertThat(pod.terminationGracePeriodSeconds).isEqualTo(90L)
                assertThat(pod.containers + pod.initContainers).allSatisfy { assertThat(it.imagePullPolicy).isEqualTo("Always") }
            }
        }

        @Test
        fun `the init container creates the data and heap directories as root and chowns them non-recursively`() {
            val pod =
                runApply(1)
                    .deployments
                    .single()
                    .spec.template.spec
            val init = pod.initContainers.single()

            assertThat(pod.securityContext.runAsUser).isEqualTo(10001L)
            assertThat(pod.securityContext.runAsGroup).isEqualTo(101L)
            assertThat(init.securityContext.runAsUser).isEqualTo(0L)
            assertThat(init.image).isEqualTo(DEFAULT_IMAGE)
            assertThat(init.command.joinToString(" "))
                .contains("mkdir -p $DATA_DIR $DATA_DIR/heap-profiles")
                .contains("chown 10001:101 $DATA_DIR $DATA_DIR/heap-profiles")
                .doesNotContain("-R")
        }
    }

    @Nested
    inner class Ring {
        @Test
        fun `seeds are the other pods' Services and broadcast is the pod's own`() {
            val run = runApply(3)

            assertThat(run.env(1))
                .containsEntry("FERROSA_INTERNODE_BROADCAST", "ferrosa-1.default.svc.cluster.local:17000")
                .containsEntry(
                    "FERROSA_SEED",
                    "ferrosa-0.default.svc.cluster.local:17000,ferrosa-2.default.svc.cluster.local:17000",
                )
            for (i in 0..2) {
                assertThat(run.env(i)["FERROSA_SEED"]).doesNotContain("ferrosa-$i.")
            }
            assertThat(run.services).allSatisfy { service ->
                assertThat(service.spec.publishNotReadyAddresses).isTrue()
                assertThat(service.spec.ports.map { it.port }).containsExactlyInAnyOrder(17000, 9042)
                assertThat(service.spec.selector).containsEntry("easydblab/ferrosa-ordinal", service.metadata.name.removePrefix("ferrosa-"))
            }
        }

        @Test
        fun `host ids are UUIDs in host order`() {
            val run = runApply(3)
            val ids = (0..2).map { run.env(it).getValue("FERROSA_HOST_ID") }

            assertThat(ids).containsExactly(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000003",
            )
            assertThat(ids.sortedBy { it }).isEqualTo(ids)
        }

        @Test
        fun `three or more hosts set the expected cluster size on every pod`() {
            val run = runApply(3)

            assertThat((0..2).map { run.env(it)["FERROSA_EXPECTED_CLUSTER_SIZE"] }).containsOnly("3")
        }

        @Test
        fun `one host has no seed and no expected size, and is not refused`() {
            val run = runApply(1)

            assertThat(run.exit).isZero()
            assertThat(run.env(0)).doesNotContainKeys("FERROSA_SEED", "FERROSA_EXPECTED_CLUSTER_SIZE")
        }

        @Test
        fun `two hosts set no expected size`() {
            val run = runApply(2)

            assertThat(run.exit).isZero()
            assertThat((0..1).map { run.env(it) }).allSatisfy { assertThat(it).doesNotContainKey("FERROSA_EXPECTED_CLUSTER_SIZE") }
        }

        @Test
        fun `client broadcasts use the pod IP and the cluster name comes from cluster-config`() {
            val container = runApply(3).container(0)
            val env = container.env.associateBy { it.name }

            assertThat(
                env
                    .getValue("POD_IP")
                    .valueFrom.fieldRef.fieldPath,
            ).isEqualTo("status.podIP")
            // Kubernetes expands $(POD_IP) only from a variable declared earlier in the list.
            assertThat(container.env.map { it.name }).containsSubsequence("POD_IP", "FERROSA_CQL_BROADCAST", "FERROSA_FLIGHT_BROADCAST")
            assertThat(env.getValue("FERROSA_CQL_BROADCAST").value).isEqualTo("$(POD_IP):9042")
            assertThat(env.getValue("FERROSA_FLIGHT_BROADCAST").value).isEqualTo("$(POD_IP):8815")
            val clusterName = env.getValue("FERROSA_CLUSTER_NAME").valueFrom.configMapKeyRef
            assertThat(clusterName.name).isEqualTo("cluster-config")
            assertThat(clusterName.key).isEqualTo("cluster_name")
        }

        @Test
        fun `every client listener binds to all interfaces`() {
            val settings = runApply(3).configMap("ferrosa-settings")

            assertThat(settings).containsAllEntriesOf(
                mapOf(
                    "FERROSA_CQL_BIND" to "0.0.0.0:9042",
                    "FERROSA_WEB_BIND" to "0.0.0.0:9090",
                    "FERROSA_POSTGRES_BIND" to "0.0.0.0:5432",
                    "FERROSA_GRAPH_BIND" to "0.0.0.0:7474",
                    "FERROSA_SPARQL_BIND" to "0.0.0.0:8080",
                    "FERROSA_FLIGHT_BIND" to "0.0.0.0:8815",
                    "FERROSA_INTERNODE_BIND" to "0.0.0.0:17000",
                    "FERROSA_DATA_DIR" to DATA_DIR,
                ),
            )
        }
    }

    @Nested
    inner class Image {
        private fun images(run: FerrosaApplyRun): Set<String> =
            run.deployments
                .flatMap { d ->
                    (d.spec.template.spec.containers + d.spec.template.spec.initContainers).map { it.image }
                }.toSet()

        @Test
        fun `the default image is the nightly tag`() {
            assertThat(images(runApply(3))).containsExactly(DEFAULT_IMAGE)
        }

        @Test
        fun `--version selects the tag`() {
            assertThat(
                images(runApply(3, mapOf("FERROSA_TAG" to "v2026.10.01.1200"))),
            ).containsExactly("ghcr.io/ferrosadb/ferrosa:v2026.10.01.1200")
        }

        @Test
        fun `--image replaces the whole reference`() {
            val ecr = "123456789012.dkr.ecr.us-west-2.amazonaws.com/ferrosa:my-build"

            assertThat(images(runApply(3, mapOf("IMAGE" to ecr)))).containsExactly(ecr)
        }
    }

    @Nested
    inner class Settings {
        @Test
        fun `local storage, the default, sets no S3 variable`() {
            val settings = runApply(3).configMap("ferrosa-settings")

            assertThat(settings.keys).noneMatch { it.startsWith("FERROSA_S3_") }
            assertThat(settings).containsEntry("FERROSA_DATA_DIR", DATA_DIR)
        }

        @Test
        fun `s3 storage points FerrosaDB at the data bucket under ferrosa and makes S3 required`() {
            val settings = runApply(3, mapOf("STORAGE_MODE" to "s3")).configMap("ferrosa-settings")

            assertThat(settings).containsAllEntriesOf(
                mapOf(
                    "FERROSA_S3_ENDPOINT" to "https://s3.us-west-2.amazonaws.com",
                    "FERROSA_S3_REGION" to "us-west-2",
                    "FERROSA_S3_BUCKET" to DATA_BUCKET,
                    "FERROSA_S3_PREFIX" to "ferrosa/",
                    "FERROSA_S3_REQUIRED" to "true",
                    "FERROSA_DATA_DIR" to DATA_DIR,
                ),
            )
            assertThat(settings.keys).noneMatch { it.contains("ACCESS_KEY") || it.contains("SECRET") }
        }

        @Test
        fun `log level sets RUST_LOG and the data center is the region`() {
            val settings = runApply(3, mapOf("LOG_LEVEL" to "debug")).configMap("ferrosa-settings")

            assertThat(settings).containsEntry("RUST_LOG", "debug").containsEntry("FERROSA_DATA_CENTER", "us-west-2")
        }

        @Test
        fun `every --env line goes into ferrosa-env, which envFrom reads after ferrosa-settings`() {
            val run = runApply(3, mapOf("EXTRA_ENV" to "KEY1=VALUE1\nRUST_LOG=trace"))

            assertThat(run.configMap("ferrosa-env")).containsExactlyInAnyOrderEntriesOf(mapOf("KEY1" to "VALUE1", "RUST_LOG" to "trace"))
            assertThat(run.container(0).envFrom.map { it.configMapRef.name }).containsExactly("ferrosa-settings", "ferrosa-env")
        }

        @Test
        fun `a repeated --env key keeps only its last value`() {
            val run = runApply(3, mapOf("EXTRA_ENV" to "A=1\nB=2\nA=3"))

            assertThat(run.exit).describedAs(run.stub.output()).isZero()
            assertThat(run.literals("ferrosa-env")).containsExactlyInAnyOrder("A" to "3", "B" to "2")
        }

        @Test
        fun `an --env key that is also a named setting goes into ferrosa-env, so it wins`() {
            val run = runApply(3, mapOf("LOG_LEVEL" to "debug", "EXTRA_ENV" to "RUST_LOG=trace"))

            assertThat(run.exit).describedAs(run.stub.output()).isZero()
            assertThat(run.literals("ferrosa-env")).containsExactly("RUST_LOG" to "trace")
            assertThat(run.configMap("ferrosa-settings")).containsEntry("RUST_LOG", "debug")
            assertThat(run.container(0).envFrom.map { it.configMapRef.name }).containsExactly("ferrosa-settings", "ferrosa-env")
        }

        @Test
        fun `both ConfigMaps carry the kit label that stop deletes by`() {
            val run = runApply(1)

            assertThat(run.stub.invocations().count { it == "label --local -f - $KIT_SELECTOR -o yaml" }).isEqualTo(2)
            assertThat(labelDelete(kit.config.stop).kinds).contains("configmap")
        }

        @Test
        fun `heap profiling sets MALLOC_CONF with the default sample rate`() {
            val settings = runApply(3, mapOf("HEAP_PROFILE" to "true")).configMap("ferrosa-settings")

            assertThat(settings.getValue("MALLOC_CONF"))
                .isEqualTo("prof:true,prof_active:true,prof_final:true,prof_prefix:$DATA_DIR/heap-profiles/ferrosa,lg_prof_sample:19")
        }

        @Test
        fun `heap sample sets lg_prof_sample`() {
            val settings = runApply(3, mapOf("HEAP_PROFILE" to "true", "HEAP_SAMPLE" to "17")).configMap("ferrosa-settings")

            assertThat(settings.getValue("MALLOC_CONF")).endsWith("lg_prof_sample:17")
        }

        @Test
        fun `no MALLOC_CONF without heap profiling`() {
            assertThat(runApply(3, mapOf("HEAP_PROFILE" to "false")).configMap("ferrosa-settings")).doesNotContainKey("MALLOC_CONF")
        }
    }

    /** ferrosa-kit: "A failed validation applies nothing". */
    @Nested
    inner class ValidationGate {
        private fun assertRefused(
            args: Map<String, String>,
            vararg message: String,
        ) {
            val run = runApply(3, args)

            assertThat(run.exit).describedAs(args.toString()).isNotZero()
            assertThat(run.stub.output()).contains("ERROR:", *message)
            assertThat(run.stub.invocations()).describedAs("kubectl calls for $args").isEmpty()
        }

        @Test
        fun `--image with --version is refused, naming both`() {
            assertRefused(mapOf("IMAGE" to "repo/img:1", "FERROSA_TAG" to "nightly"), "--image", "--version")
        }

        @Test
        fun `an invalid --storage is refused, listing local and s3`() {
            assertRefused(mapOf("STORAGE_MODE" to "ebs"), "'ebs'", "local", "s3")
        }

        @Test
        fun `--heap-sample without --heap-profile is refused`() {
            assertRefused(mapOf("HEAP_SAMPLE" to "17", "HEAP_PROFILE" to "false"), "--heap-sample", "--heap-profile")
        }

        @Test
        fun `a malformed --env line is refused, naming the line`() {
            assertRefused(mapOf("EXTRA_ENV" to "A=1\nNOEQUALS"), "NOEQUALS")
        }

        @Test
        fun `a cluster with no db nodes is refused`() {
            val run = runApply(0)

            assertThat(run.exit).isNotZero()
            assertThat(run.stub.output()).contains("ERROR:", "no db nodes")
            assertThat(run.stub.invocations()).isEmpty()
        }

        @Test
        fun `an --image that is not an image reference is refused, naming it`() {
            for (image in listOf("repo/img:1|evil", "repo/img:1\nkind: Secret", "-repo/img", "repo/img:1 x", "repo/\$(id)")) {
                assertRefused(mapOf("IMAGE" to image), "--image", image.lineSequence().first())
            }
        }

        @Test
        fun `a --version that is not a tag is refused, naming it`() {
            for (tag in listOf("v1|evil", "v1/x", ".v1", "v1 x", "a".repeat(129))) {
                assertRefused(mapOf("FERROSA_TAG" to tag), "--version", tag)
            }
        }

        @Test
        fun `an --env line for any per-pod key is refused, naming the key`() {
            for (key in PER_POD_KEYS) {
                assertRefused(mapOf("EXTRA_ENV" to "$key=x:17000"), key)
            }
        }
    }

    @Nested
    inner class Readiness {
        private fun pod(
            name: String,
            ready: Boolean = true,
            waiting: String? = null,
            initTerminatedExit: Int? = null,
            image: String = DEFAULT_IMAGE,
        ): String {
            val state = if (waiting != null) """{"waiting": {"reason": "$waiting", "message": "boom"}}""" else """{"running": {}}"""
            val init =
                initTerminatedExit
                    ?.let {
                        """, "initContainerStatuses": [{"name": "data-dir", "image": "$image", "ready": false,
                       "state": {"terminated": {"exitCode": $it, "reason": "Error"}}}]"""
                    }.orEmpty()
            return """{"metadata": {"name": "$name"},
                "spec": {"containers": [{"name": "ferrosa", "image": "$image"}]},
                "status": {"containerStatuses": [{"name": "ferrosa", "image": "$image", "ready": $ready, "state": $state}]$init}}"""
        }

        private fun pods(vararg items: String) = """{"items": [${items.joinToString(",")}]}"""

        private fun runReadiness(
            stub: StubKubectl,
            dbNodes: Int = 3,
            args: Map<String, String> = emptyMap(),
            timeoutSeconds: Int = 0,
        ): Int =
            stub.run(
                readinessStep,
                CLUSTER_ENV +
                    mapOf(
                        "DB_NODE_COUNT" to dbNodes.toString(),
                        "FERROSA_READY_TIMEOUT_SECONDS" to timeoutSeconds.toString(),
                        "FERROSA_READY_POLL_SECONDS" to "0",
                    ) + args,
            )

        @Test
        fun `returns once every pod is ready`() {
            val stub = newStub()
            stub.reply(
                "get pods",
                pods(pod("ferrosa-0", ready = false), pod("ferrosa-1"), pod("ferrosa-2")),
                pods(pod("ferrosa-0"), pod("ferrosa-1"), pod("ferrosa-2")),
            )

            assertThat(runReadiness(stub, timeoutSeconds = 30)).describedAs(stub.output()).isZero()
        }

        @Test
        fun `a missing image or tag fails at once, naming the pod and the full image`() {
            val stub = newStub()
            val image = "ghcr.io/ferrosadb/ferrosa:no-such-tag"
            stub.reply("get pods", pods(pod("ferrosa-0"), pod("ferrosa-1", ready = false, waiting = "ImagePullBackOff", image = image)))

            val exit = runReadiness(stub, timeoutSeconds = 600)

            assertThat(exit).isNotZero()
            assertThat(stub.output()).contains("ERROR:", "ferrosa-1", image)
        }

        @Test
        fun `a crash fails and prints the end of the previous log`() {
            val stub = newStub()
            stub.reply("get pods", pods(pod("ferrosa-0", ready = false, waiting = "CrashLoopBackOff")))
            stub.reply("logs ferrosa-0", "S3 access failed and FERROSA_S3_REQUIRED is set: access denied\n")

            val exit = runReadiness(stub, timeoutSeconds = 600)

            assertThat(exit).isNotZero()
            assertThat(stub.output()).contains("ERROR:", "ferrosa-0", "S3 access failed and FERROSA_S3_REQUIRED is set")
            assertThat(stub.invocations()).anySatisfy { assertThat(it).startsWith("logs ferrosa-0").contains("--previous", "-c ferrosa") }
        }

        @Test
        fun `a failed init container fails and prints its previous log`() {
            val stub = newStub()
            stub.reply("get pods", pods(pod("ferrosa-2", ready = false, initTerminatedExit = 1)))
            stub.reply("logs ferrosa-2", "sh: not found\n")

            assertThat(runReadiness(stub, timeoutSeconds = 600)).isNotZero()
            assertThat(stub.output()).contains("ferrosa-2", "data-dir", "sh: not found")
        }

        @Test
        fun `a timeout names every pod that is not ready`() {
            val stub = newStub()
            stub.reply("get pods", pods(pod("ferrosa-0"), pod("ferrosa-1", ready = false), pod("ferrosa-2", ready = false)))

            assertThat(runReadiness(stub)).isNotZero()
            assertThat(stub.output()).contains("ERROR:", "ferrosa-1, ferrosa-2").doesNotContain("ferrosa-0,")
        }

        @Test
        fun `heap profiling on a non-profiling build fails, naming the image`() {
            val stub = newStub()
            stub.reply("get pods", pods(pod("ferrosa-0")))
            stub.reply("logs ferrosa-0", "<jemalloc>: Invalid conf pair: prof:true\nstarted\n")

            val exit = runReadiness(stub, dbNodes = 1, args = mapOf("HEAP_PROFILE" to "true"))

            assertThat(exit).isNotZero()
            assertThat(stub.output()).contains("--heap-profile needs a FerrosaDB profiling build", DEFAULT_IMAGE)
        }

        @Test
        fun `heap profiling on a profiling build passes, and no heap check runs without it`() {
            val stub = newStub()
            stub.reply("get pods", pods(pod("ferrosa-0")))
            stub.reply("logs ferrosa-0", "started\n")

            assertThat(runReadiness(stub, dbNodes = 1, args = mapOf("HEAP_PROFILE" to "true"))).isZero()
            assertThat(runReadiness(newStub().also { it.reply("get pods", pods(pod("ferrosa-0"))) }, dbNodes = 1)).isZero()
        }
    }

    @Nested
    inner class ClientAccess {
        private fun nodePortServices(): List<Service> = kit.render("nodeport-service.yaml.template").filterIsInstance<Service>()

        /** ferrosa-kit: "Scaffolded README shows the endpoints". */
        @Test
        fun `the scaffolded README lists every declared endpoint's NodePort and resolves every variable`() {
            val readme = kit.renderText("README.md.template")
            val ports = kit.config.endpoints.map { it.port }

            assertThat(ports).hasSize(7)
            for (port in ports) {
                assertThat(readme).describedAs("NodePort $port").containsPattern("\\|\\s*$port\\s*\\|")
            }
            assertThat(readme).doesNotContainPattern("__[A-Z0-9_]+__")
        }

        @Test
        fun `seven NodePorts select the first pod and forward to its container ports`() {
            val services = nodePortServices()
            val containerPorts = runApply(1).container(0).ports.map { it.containerPort }

            assertThat(services.flatMap { s -> s.spec.ports.map { it.nodePort } })
                .containsExactlyInAnyOrder(30942, 30909, 30787, 30747, 30880, 30532, 30815)
            assertThat(services).allSatisfy { service ->
                assertThat(service.spec.type).isEqualTo("NodePort")
                assertThat(service.spec.selector).isEqualTo(mapOf("easydblab/ferrosa-ordinal" to "0"))
                assertThat(service.metadata.labels).containsEntry("easydblab/kit", "ferrosa")
                assertThat(containerPorts).containsAll(service.spec.ports.map { it.targetPort.intVal })
            }
            assertThat(services.flatMap { s -> s.spec.ports.map { it.targetPort.intVal } }).doesNotContain(17000)
        }

        @Test
        fun `each NodePort is declared as a db endpoint of the right type`() {
            val byPort = kit.config.endpoints.associateBy { it.port }
            val expected =
                mapOf(
                    30942 to KitEndpoint.EndpointType.CQL,
                    30909 to KitEndpoint.EndpointType.HTTP,
                    30787 to KitEndpoint.EndpointType.NATIVE,
                    30747 to KitEndpoint.EndpointType.HTTP,
                    30880 to KitEndpoint.EndpointType.HTTP,
                    30532 to KitEndpoint.EndpointType.POSTGRESQL,
                    30815 to KitEndpoint.EndpointType.NATIVE,
                )

            assertThat(byPort.mapValues { it.value.type }).isEqualTo(expected)
            assertThat(kit.config.endpoints).allSatisfy { assertThat(it.nodeType).isEqualTo("db") }
            assertThat(byPort.getValue(30909).path).isEqualTo("/")
        }

        @Test
        fun `kit info lists every start option and the seven endpoints`() {
            val output = KitInfo.buildInfoText(kit.config, emptyList())

            assertThat(output).contains("--version", "--image", "--storage", "--log-level", "--heap-profile", "--heap-sample", "--env")
            assertThat(output.lines().single { "--env" in it }).contains("EXTRA_ENV", "[repeatable]")
            assertThat(output).contains(":30942", ":30909", ":30787", ":30747", ":30880", ":30532", ":30815")
        }
    }

    @Nested
    inner class Observability {
        @Test
        fun `one pod-discovered scrape of the console port that selects every pod`() {
            val scrape =
                kit.config.metrics
                    .filterIsInstance<KitMetrics.Scrape>()
                    .single()
            val labels = runApply(3).deployments.map { it.spec.template.metadata.labels }

            assertThat(scrape.job).isEqualTo("ferrosa")
            assertThat(scrape.port).isEqualTo(9090)
            assertThat(scrape.path).isEqualTo("/metrics")
            assertThat(labels).allSatisfy { assertThat(it).containsAllEntriesOf(parseLabelSelector(scrape.podSelector)) }
        }

        @Test
        fun `the pods are ready only when readyz answers on the console port`() {
            val probe = runApply(1).container(0).readinessProbe

            assertThat(probe.httpGet.path).isEqualTo("/readyz")
            assertThat(probe.httpGet.port.intVal).isEqualTo(9090)
        }

        @Test
        fun `no cluster label and no tracing exporter are set`() {
            val run = runApply(3)
            val names = run.env(0).keys + run.configMap("ferrosa-settings").keys

            assertThat(names).noneMatch { it.startsWith("OTEL_") || it == "CLUSTER_NAME" || it.contains("TELEMETRY") }
        }
    }

    @Nested
    inner class Lifecycle {
        @Test
        fun `stop deletes the workload and keeps the claims, and uninstall deletes the claims and PVs`() {
            assertThat(labelDelete(kit.config.stop).kinds).containsExactly("deployment", "replicaset", "pod", "service", "configmap")
            assertThat(labelDelete(kit.config.stop).selector).isEqualTo(KIT_SELECTOR)
            assertThat(labelDelete(kit.config.uninstall).kinds).contains("pvc")
            assertThat(kit.config.uninstall.last()).isInstanceOf(InstallStep.PlatformPvsDelete::class.java)
            for (phase in listOf(kit.config.stop, kit.config.uninstall)) {
                assertThat(phase).noneMatch { it is InstallStep.Shell }
            }
        }

        @Test
        fun `every object start creates carries the kit label`() {
            val objects = runApply(3).objects + kit.render("nodeport-service.yaml.template")

            assertThat(objects).isNotEmpty().allSatisfy { assertThat(it.metadata.labels).containsEntry("easydblab/kit", "ferrosa") }
        }

        @Test
        fun `nothing in the kit deletes or expires objects in the data bucket`() {
            val scripts =
                (kit.config.start + kit.config.stop + kit.config.uninstall).filterIsInstance<InstallStep.Shell>().joinToString {
                    it.script
                }

            assertThat(scripts).doesNotContain("aws s3", "lifecycle", "s3 rm", "Expiration")
        }
    }

    /** The phase's one label-selected `delete` step. */
    private fun labelDelete(steps: List<InstallStep>): InstallStep.Delete =
        steps.filterIsInstance<InstallStep.Delete>().single { it.bySelector }

    private companion object {
        const val KIT_SELECTOR = "easydblab/kit=ferrosa"
        const val DEFAULT_IMAGE = "ghcr.io/ferrosadb/ferrosa:nightly"
        const val DATA_DIR = "/var/lib/ferrosa"
        const val DATA_BUCKET = "edl-data-test"
        val CLUSTER_ENV = mapOf("REGION" to "us-west-2", "BUCKET_NAME" to DATA_BUCKET)
        val PER_POD_KEYS =
            listOf(
                "FERROSA_HOST_ID",
                "FERROSA_SEED",
                "FERROSA_INTERNODE_BROADCAST",
                "FERROSA_CQL_BROADCAST",
                "FERROSA_FLIGHT_BROADCAST",
                "FERROSA_CLUSTER_NAME",
                "FERROSA_EXPECTED_CLUSTER_SIZE",
            )
    }
}
