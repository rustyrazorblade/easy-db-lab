package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.CollisionCheck
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.KitType
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.Deployment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Checks the built-in memcached kit's cross-file contracts: the `--memory` arg reaches
 * memcached's `-m`, the scrape `pod-selector` and the NodePort Service both select the memcached
 * pod, the declared endpoint is the Service's NodePort, and stop and uninstall delete by the kit
 * label that every object carries. The optional extstore path (a local PV, the extstore
 * Deployment, and the `-o` options the start step builds) is checked in [Extstore].
 */
class MemcachedKitTest : BaseKoinTest() {
    private val kit by lazy {
        BuiltinKitFixture("memcached", TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
    }

    private fun deployment(args: Map<String, String> = emptyMap()): Deployment =
        kit.render("memcached.yaml.template", args).filterIsInstance<Deployment>().single()

    private fun nodePortService(): Service = kit.render("nodeport-service.yaml.template").filterIsInstance<Service>().single()

    private fun container(
        deployment: Deployment,
        name: String,
    ) = deployment.spec.template.spec.containers
        .single { it.name == name }

    @Test
    fun `is a db kit with a collision check`() {
        assertThat(kit.config.type).isEqualTo(KitType.DB)
        assertThat(kit.config.collisionCheck).isEqualTo(CollisionCheck.ENABLED)
    }

    @Test
    fun `runs one memcached replica on a db node`() {
        val deployment = deployment()

        assertThat(deployment.spec.replicas).isEqualTo(1)
        val terms =
            deployment.spec.template.spec.affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution
                .nodeSelectorTerms
        assertThat(terms.flatMap { it.matchExpressions })
            .anySatisfy { expr ->
                assertThat(expr.key).isEqualTo("type")
                assertThat(expr.values).containsExactly("db")
            }
    }

    @Test
    fun `cache size defaults to 1024 MB`() {
        assertThat(
            kit.config.args
                .single { it.flag == "--memory" }
                .variable,
        ).isEqualTo("MEMORY_MB")
        assertThat(memcachedMemoryArg(deployment())).isEqualTo("1024")
    }

    @Test
    fun `--memory sets memcached -m`() {
        assertThat(memcachedMemoryArg(deployment(mapOf("MEMORY_MB" to "4096")))).isEqualTo("4096")
    }

    private fun memcachedMemoryArg(deployment: Deployment): String {
        val args = container(deployment, "memcached").args
        return args[args.indexOf("-m") + 1]
    }

    @Test
    fun `exporter sidecar serves metrics on the port the scrape entry declares, on the pod it selects`() {
        val deployment = deployment()
        val scrape = kit.scrapeMetrics.single()

        assertThat(scrape.port).isEqualTo(EXPORTER_PORT)
        assertThat(container(deployment, "memcached-exporter").ports.map { it.containerPort }).contains(EXPORTER_PORT)
        assertThat(deployment.spec.template.metadata.labels).containsAllEntriesOf(parseLabelSelector(scrape.podSelector))
    }

    @Test
    fun `endpoint is the NodePort that forwards to memcached`() {
        val service = nodePortService()
        val endpoint = kit.config.endpoints.single()

        assertThat(endpoint.type).isEqualTo(KitEndpoint.EndpointType.NATIVE)
        assertThat(endpoint.nodeType).isEqualTo("db")
        assertThat(service.spec.type).isEqualTo("NodePort")
        val port = service.spec.ports.single { it.nodePort == endpoint.port }
        assertThat(endpoint.port).isEqualTo(31211)
        assertThat(port.targetPort.intVal).isEqualTo(container(deployment(), "memcached").ports.single().containerPort)
        assertThat(
            deployment()
                .spec.template.metadata.labels,
        ).containsAllEntriesOf(service.spec.selector)
    }

    @Test
    fun `every object carries the kit label that stop, uninstall and the runtime select on`() {
        val objects: List<HasMetadata> = kit.render("memcached.yaml.template") + kit.render("nodeport-service.yaml.template")

        assertThat(objects).allSatisfy { assertThat(it.metadata.labels).containsEntry(KIT_LABEL, "memcached") }
        assertThat(
            deployment()
                .spec.template.metadata.labels,
        ).containsEntry(KIT_LABEL, "memcached")
        assertThat(
            parseLabelSelector(
                kit.config.runtime
                    ?.selector
                    .orEmpty(),
            ),
        ).containsEntry(KIT_LABEL, "memcached")
        for (phase in listOf(kit.config.stop, kit.config.uninstall)) {
            assertThat(phase.filterIsInstance<InstallStep.Shell>().joinToString("\n") { it.script })
                .contains("-l $KIT_LABEL=memcached")
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

        private fun run(steps: List<InstallStep>): Int =
            stub.run(
                script = steps.filterIsInstance<InstallStep.Shell>().joinToString("\n") { it.script },
                env = emptyMap(),
            )

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
            stub.respondToGet("deployment.apps/memcached\nservice/memcached\n")

            for ((phase, steps) in phases) {
                assertThat(run(steps)).describedAs(phase).isEqualTo(0)
            }
            assertThat(stub.invocations().filter { it.startsWith("get") })
                .hasSize(2)
                .allSatisfy { assertThat(it).contains("-l $KIT_LABEL=memcached") }
            assertThat(deletes())
                .hasSize(2)
                .allSatisfy { assertThat(it).contains("deployment.apps/memcached service/memcached") }
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
    fun `the RAM-only manifest creates no persistent volumes`() {
        val objects = kit.render("memcached.yaml.template")

        assertThat(objects.map { it.kind }).doesNotContain("PersistentVolumeClaim", "PersistentVolume")
        assertThat(
            deployment()
                .spec.template.spec.volumes
                .orEmpty()
                .filter { it.persistentVolumeClaim != null },
        ).isEmpty()
    }

    /**
     * memcached extstore: off unless `--extstore-size` is set. When it is set, install creates a
     * local PV on the db node's NVMe, and start applies the extstore Deployment, which mounts
     * that PV at /data, and passes memcached `-o ext_path=/data/extstore:<size>` plus any tuning
     * options that were set.
     */
    @Nested
    inner class Extstore {
        private val stub by lazy { StubKubectl(File(tempDir, "stub")) }

        private fun arg(flag: String) = kit.config.args.single { it.flag == flag }

        private fun platformPvs() =
            kit.config.install
                .filterIsInstance<InstallStep.PlatformPvs>()
                .single()

        private fun extstoreObjects() = kit.render("memcached-extstore.yaml.template")

        private fun extstoreDeployment() = extstoreObjects().filterIsInstance<Deployment>().single()

        private fun claim() = extstoreObjects().filterIsInstance<PersistentVolumeClaim>().single()

        /** Runs the start phase's shell steps with the extstore args [args] (unset ones empty). */
        private fun runStart(args: Map<String, String>): Int =
            stub.run(
                script =
                    kit.config.start
                        .filterIsInstance<InstallStep.Shell>()
                        .joinToString("\n") { it.script },
                env = EXTSTORE_FLAGS.associate { arg(it).variable to "" } + args.mapKeys { (flag, _) -> arg(flag).variable },
            )

        private fun applied() = stub.invocations().filter { it.startsWith("apply -f memcached") }

        private fun extstoreOptions(): String =
            stub
                .invocations()
                .single { it.startsWith("create configmap") }
                .substringAfter("--from-literal=$OPTIONS_KEY=")
                .substringBefore(" ")

        @Test
        fun `every extstore arg is an optional named option with no default`() {
            assertThat(EXTSTORE_FLAGS.map { arg(it) }).allSatisfy { spec ->
                assertThat(spec.flag).startsWith("--")
                assertThat(spec.default).isEmpty()
                assertThat(spec.required).isFalse()
            }
            assertThat(EXTSTORE_FLAGS.map { arg(it).variable }).doesNotHaveDuplicates()
        }

        @Test
        fun `install creates the NVMe PV only when --extstore-size is set`() {
            assertThat(platformPvs().ifSet).isEqualTo(arg("--extstore-size").variable)
            assertThat(platformPvs().nodeType).isEqualTo("db")
            assertThat(platformPvs().count).isEqualTo(1)
        }

        @Test
        fun `start with no --extstore-size applies the RAM-only manifest and no extstore options`() {
            assertThat(runStart(emptyMap())).isEqualTo(0)

            assertThat(applied()).containsExactly("apply -f memcached.yaml")
            assertThat(stub.invocations()).noneMatch { it.contains("ext_") }
        }

        @Test
        fun `tuning args without --extstore-size are ignored`() {
            assertThat(runStart(mapOf("--extstore-threads" to "8", "--extstore-item-size" to "512"))).isEqualTo(0)

            assertThat(applied()).containsExactly("apply -f memcached.yaml")
            assertThat(stub.invocations()).noneMatch { it.contains("ext_") }
        }

        @Test
        fun `--extstore-size applies the extstore manifest with ext_path on the data volume`() {
            assertThat(runStart(mapOf("--extstore-size" to "100G"))).isEqualTo(0)

            assertThat(applied()).containsExactly("apply -f memcached-extstore.yaml")
            assertThat(extstoreOptions()).isEqualTo("ext_path=/data/extstore:100G")
            assertThat(stub.invocations()).anySatisfy { assertThat(it).contains("label --local -f - $KIT_LABEL=memcached") }
        }

        @Test
        fun `only the tuning options that are set reach memcached`() {
            runStart(mapOf("--extstore-size" to "100G", "--extstore-threads" to "8", "--extstore-item-size" to "512"))

            assertThat(extstoreOptions()).isEqualTo("ext_path=/data/extstore:100G,ext_threads=8,ext_item_size=512")
        }

        @Test
        fun `every tuning option maps to its memcached ext_ option`() {
            runStart(
                mapOf(
                    "--extstore-size" to "64G",
                    "--extstore-page-size" to "64",
                    "--extstore-wbuf-size" to "8",
                    "--extstore-threads" to "4",
                    "--extstore-item-size" to "1024",
                ),
            )

            assertThat(extstoreOptions()).isEqualTo(
                "ext_path=/data/extstore:64G,ext_page_size=64,ext_wbuf_size=8,ext_threads=4,ext_item_size=1024",
            )
        }

        @Test
        fun `a malformed --extstore-size fails start before anything is applied`() {
            assertThat(listOf("lots", "100", "100GB", "-1G"))
                .allSatisfy { size ->
                    assertThat(runStart(mapOf("--extstore-size" to size))).describedAs(size).isNotEqualTo(0)
                    assertThat(stub.output()).contains("ERROR:", "'$size'")
                }
            assertThat(stub.invocations()).isEmpty()
        }

        @Test
        fun `memcached reads the options the start step publishes and runs with them as -o`() {
            val container = container(extstoreDeployment(), "memcached")
            val options = container.env.single { it.name == OPTIONS_KEY }

            assertThat(options.valueFrom.configMapKeyRef.name).isEqualTo(EXTSTORE_CONFIGMAP)
            assertThat(options.valueFrom.configMapKeyRef.key).isEqualTo(OPTIONS_KEY)
            assertThat(container.args).containsSubsequence("-o", "$($OPTIONS_KEY)")
            assertThat(container.args).containsSubsequence("-m", "1024")
            runStart(mapOf("--extstore-size" to "100G"))
            assertThat(stub.invocations()).anySatisfy { assertThat(it).startsWith("create configmap $EXTSTORE_CONFIGMAP ") }
        }

        @Test
        fun `the extstore volume is the claim bound to the PV install creates, mounted at data`() {
            val pvs = platformPvs()
            val claim = claim()
            val volume =
                extstoreDeployment()
                    .spec.template.spec.volumes
                    .single { it.persistentVolumeClaim != null }

            // platform-pvs names the PV <claim template>-<kit>-<ordinal>. Binding by name keeps the
            // claim off any other kit's PV in the same storage class.
            assertThat(claim.spec.volumeName).isEqualTo("${pvs.volumeClaimTemplateName}-memcached-0")
            assertThat(claim.spec.storageClassName).isEqualTo(pvs.storageClass)
            assertThat(
                claim.spec.resources.requests["storage"]
                    ?.toString(),
            ).isEqualTo(pvs.storageSize)
            assertThat(volume.persistentVolumeClaim.claimName).isEqualTo(claim.metadata.name)
            assertThat(container(extstoreDeployment(), "memcached").volumeMounts).anySatisfy { mount ->
                assertThat(mount.name).isEqualTo(volume.name)
                assertThat(mount.mountPath).isEqualTo("/data")
            }
        }

        /**
         * The PV directory is created by root on the node; the memcached image runs as the
         * memcache user (uid/gid 11211), which could not create the extstore file without fsGroup.
         */
        @Test
        fun `memcached can write to the root-owned volume`() {
            assertThat(
                extstoreDeployment()
                    .spec.template.spec.securityContext.fsGroup,
            ).isEqualTo(MEMCACHE_GID)
        }

        @Test
        fun `a restart never runs two memcached processes on one extstore file`() {
            assertThat(extstoreDeployment().spec.strategy.type).isEqualTo("Recreate")
        }

        @Test
        fun `the extstore Deployment keeps the RAM-only Deployment's name, labels, ports and exporter`() {
            val ram = deployment()
            val ext = extstoreDeployment()

            assertThat(ext.metadata.name).isEqualTo(ram.metadata.name)
            assertThat(ext.spec.replicas).isEqualTo(1)
            assertThat(ext.spec.template.metadata.labels).isEqualTo(ram.spec.template.metadata.labels)
            assertThat(ext.spec.template.spec.affinity).isEqualTo(ram.spec.template.spec.affinity)
            assertThat(
                ext.spec.template.spec.containers
                    .map { it.name to it.ports },
            ).isEqualTo(
                ram.spec.template.spec.containers
                    .map { it.name to it.ports },
            )
            assertThat(container(ext, "memcached-exporter")).isEqualTo(container(ram, "memcached-exporter"))
        }

        @Test
        fun `every extstore object carries the kit label`() {
            assertThat(extstoreObjects()).allSatisfy { assertThat(it.metadata.labels).containsEntry(KIT_LABEL, "memcached") }
        }

        @Test
        fun `uninstall deletes the kit's PVC and its PV`() {
            assertThat(
                kit.config.uninstall
                    .filterIsInstance<InstallStep.Shell>()
                    .joinToString("\n") { it.script },
            ).contains("pvc", "-l $KIT_LABEL=memcached")
            assertThat(kit.config.uninstall.last()).isInstanceOf(InstallStep.PlatformPvsDelete::class.java)
        }
    }

    private companion object {
        const val KIT_LABEL = "easydblab/kit"
        const val EXPORTER_PORT = 9150
        const val OPTIONS_KEY = "EXTSTORE_OPTIONS"
        const val EXTSTORE_CONFIGMAP = "memcached-extstore-options"
        const val MEMCACHE_GID = 11211L
        val EXTSTORE_FLAGS =
            listOf("--extstore-size", "--extstore-page-size", "--extstore-wbuf-size", "--extstore-threads", "--extstore-item-size")
    }
}
