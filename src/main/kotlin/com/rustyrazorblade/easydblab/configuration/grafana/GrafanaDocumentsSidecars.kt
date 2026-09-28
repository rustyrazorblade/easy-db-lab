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
 * The two containers that let the browser read a test's documents from the account bucket through
 * the Grafana pod, which runs on the control node's host network:
 *
 * - an `aws-sigv4-proxy` bound to `127.0.0.1`, which signs S3 requests with the instance role for
 *   the bucket's region; binding to loopback keeps other hosts on the VPC or tailnet from using the
 *   cluster role through it;
 * - a read-only nginx on [Constants.Grafana.Documents.WEB_PORT], which forwards only `GET` requests
 *   for normalized paths under `/reports/` to the proxy, with the bucket fixed and the query string
 *   dropped. Its configuration is a classpath resource and ships in its own ConfigMap, so a change to
 *   it rolls the pod through the config hash.
 */
class GrafanaDocumentsSidecars(
    private val templateService: TemplateService,
) {
    companion object {
        /** The ConfigMap holding the web server's configuration. */
        const val CONFIGMAP_NAME = "grafana-documents-web"

        /** The resource the web server's configuration is rendered from. */
        const val CONFIG_RESOURCE = "documents-nginx.conf"

        private const val CONFIG_KEY = "nginx.conf"
        private const val VOLUME = "documents-web-config"
        private const val NAMESPACE = "default"
    }

    /** The web server's rendered configuration for [documents]. */
    fun webServerConfig(documents: DocumentsBucket): String =
        templateService
            .fromResource(GrafanaDocumentsSidecars::class.java, CONFIG_RESOURCE)
            .substitute(
                mapOf(
                    "DOCUMENTS_WEB_PORT" to
                        Constants.Grafana.Documents.WEB_PORT
                            .toString(),
                    "DOCUMENTS_PROXY_PORT" to
                        Constants.Grafana.Documents.PROXY_PORT
                            .toString(),
                    "DOCUMENTS_BUCKET" to documents.bucket,
                ),
            )

    /** The ConfigMap with the web server's configuration. */
    fun configMap(documents: DocumentsBucket): ConfigMap =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(CONFIGMAP_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", "grafana")
            .endMetadata()
            .addToData(CONFIG_KEY, webServerConfig(documents))
            .build()

    /** The signing proxy and the web server. */
    fun containers(documents: DocumentsBucket): List<Container> = listOf(proxy(documents), webServer())

    /** The volume the web server's configuration is mounted from. */
    fun volume(): Volume =
        VolumeBuilder()
            .withName(VOLUME)
            .withNewConfigMap()
            .withName(CONFIGMAP_NAME)
            .endConfigMap()
            .build()

    private fun proxy(documents: DocumentsBucket): Container =
        ContainerBuilder()
            .withName("documents-sigv4-proxy")
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

    private fun webServer(): Container =
        ContainerBuilder()
            .withName("documents-web")
            .withImage(Constants.Grafana.Documents.WEB_SERVER_IMAGE)
            .addNewPort()
            .withContainerPort(Constants.Grafana.Documents.WEB_PORT)
            .withHostPort(Constants.Grafana.Documents.WEB_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewVolumeMount()
            .withName(VOLUME)
            .withMountPath("/etc/nginx/nginx.conf")
            .withSubPath(CONFIG_KEY)
            .withReadOnly(true)
            .endVolumeMount()
            .build()
}
