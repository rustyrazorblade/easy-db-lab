package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.services.InstallStep
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.Deployment
import java.io.File

/**
 * One run of the FerrosaDB kit's first `start` shell step (validate the options, write the two
 * ConfigMaps, render `ferrosa-node.yaml` once for each db host) against a [StubKubectl] in [dir],
 * with `ferrosa-node.yaml` rendered from the packaged template as `kit install` writes it. Exposes
 * what the step would have applied, so unit and integration tests read the same objects.
 */
class FerrosaApplyRun(
    kit: BuiltinKitFixture,
    dir: File,
    dbNodes: Int,
    env: Map<String, String>,
) {
    /** The stub the step ran against. */
    val stub = StubKubectl(dir)

    /** The step's exit code. */
    val exit: Int

    init {
        File(dir, "ferrosa-node.yaml").writeText(kit.renderText("ferrosa-node.yaml.template"))
        val script =
            kit.config.start
                .filterIsInstance<InstallStep.Shell>()
                .first()
                .script
        exit = stub.run(script, env + ("DB_NODE_COUNT" to dbNodes.toString()))
    }

    /** Every object piped to `kubectl apply -f -`. */
    val objects: List<HasMetadata> by lazy { stub.stdinOf("apply -f -").flatMap { parseManifests(it) } }

    val deployments: List<Deployment> get() = objects.filterIsInstance<Deployment>().sortedBy { it.metadata.name }

    val claims: List<PersistentVolumeClaim> get() = objects.filterIsInstance<PersistentVolumeClaim>()

    val services: List<Service> get() = objects.filterIsInstance<Service>()

    /** The `--from-literal` entries of the ConfigMap named [name]. */
    fun configMap(name: String): Map<String, String> =
        stub
            .invocations()
            .single { it.startsWith("create configmap $name ") }
            .split(" ")
            .filter { it.startsWith(FROM_LITERAL) }
            .map { it.removePrefix(FROM_LITERAL) }
            .associate { it.substringBefore("=") to it.substringAfter("=") }

    /** The FerrosaDB container of pod [ordinal]. */
    fun container(ordinal: Int): Container =
        deployments
            .single { it.metadata.name == "ferrosa-$ordinal" }
            .spec.template.spec.containers
            .single { it.name == "ferrosa" }

    /** The literal env values of pod [ordinal]'s FerrosaDB container (null for valueFrom). */
    fun env(ordinal: Int): Map<String, String?> = container(ordinal).env.associate { it.name to it.value }

    private companion object {
        const val FROM_LITERAL = "--from-literal="
    }
}
