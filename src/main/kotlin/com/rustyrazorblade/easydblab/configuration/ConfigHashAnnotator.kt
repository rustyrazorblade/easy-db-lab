package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

/**
 * Stamps each workload's pod template with a hash of the pod template itself and of the ConfigMaps
 * it reads, under [Constants.K8s.CONFIG_HASH_ANNOTATION].
 *
 * Kubernetes rolls a Deployment, DaemonSet or StatefulSet when its pod template changes, and only
 * then. A workload whose ConfigMap changed but whose template did not keeps running the old
 * configuration, which is why every observability deploy used to rollout-restart every workload —
 * restarting Tempo and Pyroscope on every dashboard edit. With the hash on the template, a
 * configuration change is a template change and rolls exactly the workloads that read it; an
 * unchanged workload is left running.
 *
 * The hash also covers the rendered pod template (image, probes, env, volumes, labels, other
 * annotations — everything but the hash annotation), so it changes exactly when Kubernetes would
 * roll the workload. [com.rustyrazorblade.easydblab.services.ConfigChangeReport] compares it with the
 * running workload's hash to say whether a deploy rolls it; a hash of the ConfigMaps alone reported
 * an image or probe change as "unchanged" while the workload rolled. The template is serialized
 * with object keys sorted, so map insertion order does not change the hash. Both sides of that
 * comparison are hashes of what easy-db-lab rendered, never of the server-defaulted object.
 *
 * A workload reads a ConfigMap by mounting it as a volume, by `env[].valueFrom.configMapKeyRef`, or
 * by `envFrom[].configMapRef`, in any container or init container. The data is looked up among the
 * ConfigMaps being applied alongside it, then in [annotate]'s `external` map for ConfigMaps built
 * elsewhere (the runtime `cluster-config`). Settings written into the pod spec itself need no hash:
 * they are already part of the template.
 *
 * A reference to a ConfigMap whose contents are not known is refused: its hash could not change when
 * the ConfigMap does, so the workload would silently keep its old configuration.
 */
object ConfigHashAnnotator {
    /**
     * Returns [resources] with every Deployment, DaemonSet and StatefulSet annotated; other resources
     * are returned unchanged. The inputs are not modified.
     *
     * @param external ConfigMap data, by name, for ConfigMaps not among [resources].
     * @throws IllegalArgumentException naming each workload and the ConfigMap it reads whose contents
     *   are neither among [resources] nor in [external].
     */
    fun annotate(
        resources: List<HasMetadata>,
        external: Map<String, Map<String, String>> = emptyMap(),
    ): List<HasMetadata> {
        val available = external + resources.filterIsInstance<ConfigMap>().associate { it.metadata.name to it.data.orEmpty() }
        val unhashed =
            resources.flatMap { resource ->
                val pod = podSpecOf(resource) ?: return@flatMap emptyList()
                referencedConfigMaps(pod).filterNot { it in available }.map { "${resource.kind}/${resource.metadata.name} reads $it" }
            }
        require(unhashed.isEmpty()) {
            "ConfigMap contents unknown, so these workloads would not roll when they change: ${unhashed.joinToString("; ")}"
        }
        return resources.map { resource ->
            when (resource) {
                is Deployment ->
                    DeploymentBuilder(resource)
                        .editSpec()
                        .editTemplate()
                        .editOrNewMetadata()
                        .addToAnnotations(Constants.K8s.CONFIG_HASH_ANNOTATION, hash(resource.spec.template, available))
                        .endMetadata()
                        .endTemplate()
                        .endSpec()
                        .build()

                is DaemonSet ->
                    DaemonSetBuilder(resource)
                        .editSpec()
                        .editTemplate()
                        .editOrNewMetadata()
                        .addToAnnotations(Constants.K8s.CONFIG_HASH_ANNOTATION, hash(resource.spec.template, available))
                        .endMetadata()
                        .endTemplate()
                        .endSpec()
                        .build()

                is StatefulSet ->
                    StatefulSetBuilder(resource)
                        .editSpec()
                        .editTemplate()
                        .editOrNewMetadata()
                        .addToAnnotations(Constants.K8s.CONFIG_HASH_ANNOTATION, hash(resource.spec.template, available))
                        .endMetadata()
                        .endTemplate()
                        .endSpec()
                        .build()

                else -> resource
            }
        }
    }

    /** The pod spec of a Deployment, DaemonSet or StatefulSet; null for any other resource. */
    private fun podSpecOf(resource: HasMetadata): PodSpec? =
        when (resource) {
            is Deployment -> resource.spec.template.spec
            is DaemonSet -> resource.spec.template.spec
            is StatefulSet -> resource.spec.template.spec
            else -> null
        }

    /** The names of every ConfigMap [pod] reads, sorted. */
    private fun referencedConfigMaps(pod: PodSpec): List<String> {
        val containers: List<Container> = pod.containers.orEmpty() + pod.initContainers.orEmpty()
        val fromVolumes = pod.volumes.orEmpty().mapNotNull { it.configMap?.name }
        val fromEnv = containers.flatMap { it.env.orEmpty() }.mapNotNull { it.valueFrom?.configMapKeyRef?.name }
        val fromEnvFrom = containers.flatMap { it.envFrom.orEmpty() }.mapNotNull { it.configMapRef?.name }
        return (fromVolumes + fromEnv + fromEnvFrom).distinct().sorted()
    }

    /**
     * SHA-256 over the canonical pod template and the referenced ConfigMaps' names and data, in a
     * canonical order.
     */
    private fun hash(
        template: PodTemplateSpec,
        available: Map<String, Map<String, String>>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("template:${canonicalTemplate(template)}\u0000".toByteArray())
        for (name in referencedConfigMaps(template.spec)) {
            digest.update("configmap:$name\u0000".toByteArray())
            available.getValue(name).toSortedMap().forEach { (key, value) ->
                digest.update("$key\u0000$value\u0000".toByteArray())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** [template] as JSON with object keys sorted and the hash annotation left out. */
    private fun canonicalTemplate(template: PodTemplateSpec): String {
        val json = Json.parseToJsonElement(Serialization.asJson(template)).jsonObject
        return sortedKeys(withoutHashAnnotation(json)).toString()
    }

    private fun withoutHashAnnotation(template: JsonObject): JsonObject {
        val metadata = template["metadata"] as? JsonObject ?: return template
        val annotations = metadata["annotations"] as? JsonObject ?: return template
        val kept = JsonObject(annotations - Constants.K8s.CONFIG_HASH_ANNOTATION)
        val newMetadata =
            if (kept.isEmpty()) JsonObject(metadata - "annotations") else JsonObject(metadata + ("annotations" to kept))
        return JsonObject(template + ("metadata" to newMetadata))
    }

    private fun sortedKeys(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.toSortedMap().mapValues { (_, value) -> sortedKeys(value) })
            is JsonArray -> JsonArray(element.map { sortedKeys(it) })
            else -> element
        }
}
