package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.grafana.DocumentsBucket
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.apps.Deployment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The local stack runs the cluster's versions. The cluster's images are read from the resources its
 * builders produce, so a pin the cluster changes and the local stack does not follow fails here.
 */
class LocalImagesTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "test", versions = mutableMapOf(), s3Bucket = "acct"))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    private fun clusterImages(): Set<String> {
        val templates: TemplateService = getKoin().get()
        val resources =
            MimirManifestBuilder(templates).buildAllResources() +
                LokiManifestBuilder(templates).buildAllResources() +
                TempoManifestBuilder(templates).buildAllResources() +
                GrafanaManifestBuilder(templates).buildAllResources(DocumentsBucket("acct", "us-west-2"))
        return resources
            .filterIsInstance<Deployment>()
            .flatMap {
                it.spec.template.spec.containers +
                    it.spec.template.spec.initContainers
                        .orEmpty()
            }.map { it.image }
            .toSet()
    }

    @Test
    fun `every component the cluster also runs uses the image the cluster deploys`() {
        val deployed = clusterImages()

        assertThat(LocalImages.CLUSTER_PINS).allSatisfy { component, image ->
            assertThat(deployed).describedAs(component).contains(image)
        }
    }

    @Test
    fun `every image only the local stacks run is a released version`() {
        assertThat(LocalImages.LOCAL_PINS.values).allSatisfy { image ->
            assertThat(image).matches(".+:v?[0-9]+\\.[0-9]+\\.[0-9]+(-[a-z]+)?")
        }
    }
}
