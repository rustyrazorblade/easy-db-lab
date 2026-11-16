package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.ProbeBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Kubernetes rolls a workload when its pod template changes, and only then. A workload whose
 * ConfigMap changed but whose template did not keeps running the old configuration, which is why
 * every deploy used to restart every workload. Stamping each pod template with a hash of the
 * configuration it reads makes a config change a template change, and leaves an unchanged workload
 * untouched.
 */
class ConfigHashAnnotatorTest {
    private fun configMap(
        name: String,
        vararg data: Pair<String, String>,
    ): ConfigMap =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace("default")
            .endMetadata()
            .withData<String, String>(mapOf(*data))
            .build()

    /** A Deployment mounting [volumeConfigMap] and reading one key of [envConfigMap]. */
    private fun deployment(
        volumeConfigMap: String,
        envConfigMap: String = "cluster-config",
        image: String = "tempo:1",
    ): Deployment =
        DeploymentBuilder()
            .withNewMetadata()
            .withName("tempo")
            .withNamespace("default")
            .endMetadata()
            .withNewSpec()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app", "tempo")
            .endMetadata()
            .withNewSpec()
            .addNewContainer()
            .withName("tempo")
            .withImage(image)
            .addNewEnv()
            .withName("S3_BUCKET")
            .withNewValueFrom()
            .withNewConfigMapKeyRef()
            .withName(envConfigMap)
            .withKey("s3_bucket")
            .endConfigMapKeyRef()
            .endValueFrom()
            .endEnv()
            .endContainer()
            .addNewVolume()
            .withName("config")
            .withNewConfigMap()
            .withName(volumeConfigMap)
            .endConfigMap()
            .endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()

    private fun daemonSet(
        configMapName: String,
        literalEnv: String = "one",
    ): DaemonSet =
        DaemonSetBuilder()
            .withNewMetadata()
            .withName("otel-collector")
            .withNamespace("default")
            .endMetadata()
            .withNewSpec()
            .withNewTemplate()
            .withNewSpec()
            .addNewContainer()
            .withName("otel")
            .addNewEnv()
            .withName("LITERAL")
            .withValue(literalEnv)
            .endEnv()
            .addNewEnvFrom()
            .withNewConfigMapRef()
            .withName(configMapName)
            .endConfigMapRef()
            .endEnvFrom()
            .endContainer()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()

    private val clusterConfig = mapOf("cluster-config" to mapOf("s3_bucket" to "acct"))

    private fun hashOf(
        resources: List<HasMetadata>,
        name: String,
        external: Map<String, Map<String, String>> = clusterConfig,
    ): String? {
        val workload = ConfigHashAnnotator.annotate(resources, external).single { it.metadata.name == name }
        val template =
            when (workload) {
                is Deployment -> workload.spec.template
                is DaemonSet -> workload.spec.template
                else -> error("not a workload: $workload")
            }
        return template.metadata?.annotations?.get(Constants.K8s.CONFIG_HASH_ANNOTATION)
    }

    @Test
    fun `a workload carries a hash of the configuration it reads`() {
        val hash = hashOf(listOf(configMap("tempo-config", "tempo.yaml" to "a"), deployment("tempo-config")), "tempo")

        assertThat(hash).isNotBlank()
    }

    @Test
    fun `the same configuration gives the same hash, so an unchanged workload is not rolled`() {
        val first = hashOf(listOf(configMap("tempo-config", "tempo.yaml" to "a"), deployment("tempo-config")), "tempo")
        val second = hashOf(listOf(configMap("tempo-config", "tempo.yaml" to "a"), deployment("tempo-config")), "tempo")

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `a change to a mounted ConfigMap changes the hash`() {
        val before = hashOf(listOf(configMap("tempo-config", "tempo.yaml" to "a"), deployment("tempo-config")), "tempo")
        val after = hashOf(listOf(configMap("tempo-config", "tempo.yaml" to "b"), deployment("tempo-config")), "tempo")

        assertThat(after).isNotEqualTo(before)
    }

    @Test
    fun `a change to a ConfigMap read through env, built elsewhere, changes the hash`() {
        val resources = listOf(configMap("tempo-config", "tempo.yaml" to "a"), deployment("tempo-config"))

        val before = hashOf(resources, "tempo", mapOf("cluster-config" to mapOf("s3_bucket" to "one")))
        val after = hashOf(resources, "tempo", mapOf("cluster-config" to mapOf("s3_bucket" to "two")))

        assertThat(after).isNotEqualTo(before)
    }

    @Test
    fun `a change to a ConfigMap the workload does not read leaves its hash alone`() {
        val before =
            hashOf(
                listOf(configMap("tempo-config", "tempo.yaml" to "a"), configMap("grafana-config", "x" to "1"), deployment("tempo-config")),
                "tempo",
            )
        val after =
            hashOf(
                listOf(configMap("tempo-config", "tempo.yaml" to "a"), configMap("grafana-config", "x" to "2"), deployment("tempo-config")),
                "tempo",
            )

        assertThat(after).isEqualTo(before)
    }

    @Test
    fun `envFrom references are hashed for a DaemonSet too`() {
        val before = hashOf(listOf(configMap("otel-env", "k" to "1"), daemonSet("otel-env")), "otel-collector")
        val after = hashOf(listOf(configMap("otel-env", "k" to "2"), daemonSet("otel-env")), "otel-collector")

        assertThat(after).isNotEqualTo(before)
    }

    @Test
    fun `the order of keys in a ConfigMap does not change the hash`() {
        val before = hashOf(listOf(configMap("tempo-config", "a" to "1", "b" to "2"), deployment("tempo-config")), "tempo")
        val after = hashOf(listOf(configMap("tempo-config", "b" to "2", "a" to "1"), deployment("tempo-config")), "tempo")

        assertThat(after).isEqualTo(before)
    }

    @Test
    fun `existing pod template annotations are kept`() {
        val withAnnotation =
            deployment("tempo-config").also {
                it.spec.template.metadata.annotations = mutableMapOf("keep" to "me")
            }

        val annotated =
            ConfigHashAnnotator
                .annotate(listOf(configMap("tempo-config", "a" to "1"), withAnnotation), clusterConfig)
                .filterIsInstance<Deployment>()
                .single()

        assertThat(annotated.spec.template.metadata.annotations)
            .containsEntry("keep", "me")
            .containsKey(Constants.K8s.CONFIG_HASH_ANNOTATION)
    }

    /**
     * Kubernetes rolls on any pod-template change, not only a ConfigMap change. A probe, image or env
     * change that left the hash alone was reported "unchanged, left running" while the workload rolled.
     */
    @Test
    fun `a probe change to the pod template changes the hash`() {
        val resources = listOf(configMap("tempo-config", "a" to "1"), deployment("tempo-config"))
        val withProbe =
            listOf(
                configMap("tempo-config", "a" to "1"),
                deployment("tempo-config").also { d ->
                    d.spec.template.spec.containers
                        .single()
                        .startupProbe =
                        ProbeBuilder()
                            .withNewHttpGet()
                            .withPath("/ready")
                            .withPort(IntOrString(3200))
                            .endHttpGet()
                            .build()
                },
            )

        assertThat(hashOf(withProbe, "tempo")).isNotEqualTo(hashOf(resources, "tempo"))
    }

    @Test
    fun `an image change to the pod template changes the hash`() {
        val before = listOf(configMap("tempo-config", "a" to "1"), deployment("tempo-config", image = "tempo:1"))
        val after = listOf(configMap("tempo-config", "a" to "1"), deployment("tempo-config", image = "tempo:2"))

        assertThat(hashOf(after, "tempo")).isNotEqualTo(hashOf(before, "tempo"))
    }

    @Test
    fun `a literal env change to the pod template changes the hash`() {
        val before = listOf(configMap("otel-env", "k" to "1"), daemonSet("otel-env", literalEnv = "one"))
        val after = listOf(configMap("otel-env", "k" to "1"), daemonSet("otel-env", literalEnv = "two"))

        assertThat(hashOf(after, "otel-collector")).isNotEqualTo(hashOf(before, "otel-collector"))
    }

    @Test
    fun `the insertion order of pod template labels does not change the hash`() {
        fun labelled(vararg labels: Pair<String, String>) =
            deployment("tempo-config").also { it.spec.template.metadata.labels = linkedMapOf(*labels) }

        val before = hashOf(listOf(configMap("tempo-config", "a" to "1"), labelled("a" to "1", "b" to "2")), "tempo")
        val after = hashOf(listOf(configMap("tempo-config", "a" to "1"), labelled("b" to "2", "a" to "1")), "tempo")

        assertThat(after).isEqualTo(before)
    }

    /** The hash annotation is part of the template, so it must not feed back into its own value. */
    @Test
    fun `annotating an already annotated workload keeps its hash`() {
        val resources = listOf(configMap("tempo-config", "a" to "1"), deployment("tempo-config"))
        val once = ConfigHashAnnotator.annotate(resources, clusterConfig)

        assertThat(hashOf(once, "tempo")).isEqualTo(hashOf(resources, "tempo"))
    }

    /**
     * A reference whose contents are unknown cannot be hashed, so the workload would never roll when
     * that ConfigMap changes, and nothing would say so. That is a deploy bug, not a runtime state.
     */
    @Test
    fun `a workload reading a ConfigMap whose contents are unknown is refused, naming both`() {
        assertThatThrownBy {
            ConfigHashAnnotator.annotate(listOf(configMap("tempo-config", "a" to "1"), deployment("tempo-config", "grafana-datasources")))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Deployment/tempo")
            .hasMessageContaining("grafana-datasources")
    }
}
