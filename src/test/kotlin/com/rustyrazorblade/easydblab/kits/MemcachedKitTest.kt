package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.KitType
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.CollisionCheck
import io.fabric8.kubernetes.api.model.HasMetadata
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
 * label that every object carries.
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
    fun `creates no persistent volumes`() {
        val objects = kit.render("memcached.yaml.template")

        assertThat(objects.map { it.kind }).doesNotContain("PersistentVolumeClaim", "PersistentVolume")
        assertThat(
            deployment()
                .spec.template.spec.volumes
                .orEmpty()
                .filter { it.persistentVolumeClaim != null },
        ).isEmpty()
        assertThat(kit.config.install + kit.config.start).noneMatch { it is InstallStep.PlatformPvs }
    }

    private companion object {
        const val KIT_LABEL = "easydblab/kit"
        const val EXPORTER_PORT = 9150
    }
}
