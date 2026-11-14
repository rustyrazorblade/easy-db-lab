package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeBuilder

/**
 * The account bucket that holds the test documents, and the region to sign requests to it for.
 *
 * @property bucket The account bucket.
 * @property region The bucket's own region, which may differ from the cluster's.
 */
data class DocumentsBucket(
    val bucket: String,
    val region: String,
)

/**
 * The two containers in the Grafana pod that put Grafana and a test's documents behind one port, so
 * the browser reaches both over one connection (a `127.0.0.1` port-forward, or the SOCKS path on AWS)
 * and the documents iframe is same-origin:
 *
 * - an nginx front on Grafana's client port, [Constants.K8s.GRAFANA_PORT], the port the grafana
 *   Service targets and the one the control node publishes on AWS. It forwards only `GET` and `HEAD`
 *   for normalized paths under `/reports/` to the signing proxy, with the bucket fixed and the query
 *   string dropped, and every other request to Grafana on its loopback port,
 *   [Constants.Grafana.INTERNAL_PORT], keeping the Host header and the websocket upgrade Grafana Live
 *   needs. Its configuration is a classpath resource and ships in its own ConfigMap, so a change to
 *   it rolls the pod through the config hash;
 * - an `aws-sigv4-proxy` bound to `127.0.0.1`, which signs S3 requests with the default credential
 *   chain for the bucket's region; binding to loopback keeps other hosts on the VPC or tailnet from
 *   using the pod's credentials through it.
 */
class GrafanaFront(
    private val templateService: TemplateService,
) {
    companion object {
        /** The ConfigMap holding the front's configuration. */
        const val CONFIGMAP_NAME = "grafana-front"

        /** The resource the front's configuration is rendered from. */
        const val CONFIG_RESOURCE = "grafana-front-nginx.conf"

        /** The pod volume the front's configuration is mounted from. */
        const val CONFIG_VOLUME = "grafana-front-config"

        /** The nginx container that holds Grafana's client port. */
        const val FRONT_CONTAINER = "grafana-front"

        /** The signing proxy the front sends `/reports/` to. */
        const val PROXY_CONTAINER = "documents-sigv4-proxy"

        /** Grafana's health endpoint, which the front's readiness probe reaches through the front. */
        const val HEALTH_PATH = "/api/health"

        private const val CONFIG_KEY = "nginx.conf"
        private const val NAMESPACE = "default"
        private const val READINESS_PERIOD = 10
    }

    /** The front's rendered configuration for [documents]. */
    fun config(documents: DocumentsBucket): String =
        templateService
            .fromResource(GrafanaFront::class.java, CONFIG_RESOURCE)
            .substitute(
                mapOf(
                    "GRAFANA_PORT" to Constants.K8s.GRAFANA_PORT.toString(),
                    "GRAFANA_INTERNAL_PORT" to Constants.Grafana.INTERNAL_PORT.toString(),
                    "DOCUMENTS_PROXY_PORT" to
                        Constants.Grafana.Documents.PROXY_PORT
                            .toString(),
                    "DOCUMENTS_BUCKET" to documents.bucket,
                ),
            )

    /** The ConfigMap with the front's configuration. */
    fun configMap(documents: DocumentsBucket): ConfigMap =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(CONFIGMAP_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", "grafana")
            .endMetadata()
            .addToData(CONFIG_KEY, config(documents))
            .build()

    /** The signing proxy and the front. */
    fun containers(documents: DocumentsBucket): List<Container> = listOf(proxy(documents), front())

    /** The volume the front's configuration is mounted from. */
    fun volume(): Volume =
        VolumeBuilder()
            .withName(CONFIG_VOLUME)
            .withNewConfigMap()
            .withName(CONFIGMAP_NAME)
            .endConfigMap()
            .build()

    private fun proxy(documents: DocumentsBucket): Container =
        ContainerBuilder()
            .withName(PROXY_CONTAINER)
            .withImage(Constants.Grafana.Documents.SIGV4_PROXY_IMAGE)
            .withArgs(
                "--name",
                "s3",
                "--region",
                documents.region,
                "--host",
                "s3.${documents.region}.amazonaws.com",
                "--port",
                "127.0.0.1:${Constants.Grafana.Documents.PROXY_PORT}",
            ).addNewPort()
            .withContainerPort(Constants.Grafana.Documents.PROXY_PORT)
            .withProtocol("TCP")
            .endPort()
            .build()

    /**
     * The front. Its readiness probe asks Grafana's health through the front itself, so the pod is
     * Ready, and the Service sends it traffic, only once the whole path to Grafana answers.
     */
    private fun front(): Container =
        ContainerBuilder()
            .withName(FRONT_CONTAINER)
            .withImage(Constants.Grafana.Documents.WEB_SERVER_IMAGE)
            .addNewPort()
            .withName("http")
            .withContainerPort(Constants.K8s.GRAFANA_PORT)
            .withHostPort(Constants.K8s.GRAFANA_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewVolumeMount()
            .withName(CONFIG_VOLUME)
            .withMountPath("/etc/nginx/nginx.conf")
            .withSubPath(CONFIG_KEY)
            .withReadOnly(true)
            .endVolumeMount()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath(HEALTH_PATH)
            .withNewPort(Constants.K8s.GRAFANA_PORT)
            .endHttpGet()
            .withPeriodSeconds(READINESS_PERIOD)
            .endReadinessProbe()
            .build()
}
