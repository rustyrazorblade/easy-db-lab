package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.sidecar.SidecarManifestBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import software.amazon.awssdk.services.ecr.EcrClient
import java.util.Base64

@Serializable
private data class DockerConfigAuth(
    val username: String,
    val password: String,
    val auth: String,
)

@Serializable
private data class DockerConfig(
    val auths: Map<String, DockerConfigAuth>,
)

/**
 * Service for managing the Cassandra sidecar as a K3s DaemonSet.
 *
 * The sidecar runs as a DaemonSet on all database nodes (nodeSelector: type=db).
 * deploy() applies the DaemonSet once and K3s schedules it across all db nodes.
 */
interface SidecarService {
    /**
     * Deploys the sidecar DaemonSet to all database nodes via K3s.
     *
     * @param controlHost The control node running K3s
     * @param image Container image to deploy (e.g. ghcr.io/apache/cassandra-sidecar:latest)
     */
    fun deploy(
        controlHost: ClusterHost,
        image: String,
    ): Result<Unit>

    /**
     * Removes the sidecar DaemonSet and its ConfigMap from K3s.
     *
     * @param controlHost The control node running K3s
     */
    fun undeploy(controlHost: ClusterHost): Result<Unit>

    /**
     * Performs a rolling restart of the sidecar DaemonSet pods.
     *
     * @param controlHost The control node running K3s
     */
    fun restart(controlHost: ClusterHost): Result<Unit>
}

/**
 * Default implementation of SidecarService using K3s via K8sService.
 *
 * @property k8sService Service for K8s operations
 * @property manifestBuilder Builder for sidecar K8s resources
 * @property clusterStateManager Provides cluster name for Pyroscope labels
 * @property ecrClient AWS ECR client for fetching registry auth tokens
 */
class DefaultSidecarService(
    private val k8sService: K8sService,
    private val manifestBuilder: SidecarManifestBuilder,
    private val clusterStateManager: ClusterStateManager,
    private val ecrClient: EcrClient,
) : SidecarService {
    private val log = KotlinLogging.logger {}

    companion object {
        private const val APP_LABEL_KEY = "app.kubernetes.io/name"
        private const val ECR_PULL_SECRET_NAME = "ecr-pull-secret"
    }

    override fun deploy(
        controlHost: ClusterHost,
        image: String,
    ): Result<Unit> =
        runCatching {
            val clusterName = clusterStateManager.load().name

            val pullSecretName =
                if (isEcrImage(image)) {
                    createEcrPullSecret(controlHost, image)
                    ECR_PULL_SECRET_NAME
                } else {
                    ""
                }

            log.info { "Deploying sidecar DaemonSet image=$image to K3s" }
            val resources =
                manifestBuilder.buildAllResources(
                    image = image,
                    controlNodeIp = controlHost.privateIp,
                    clusterName = clusterName,
                    imagePullSecretName = pullSecretName,
                )
            for (resource in resources) {
                k8sService.applyResource(controlHost, resource).getOrThrow()
            }
            log.info { "Sidecar DaemonSet deployed successfully" }
        }

    override fun undeploy(controlHost: ClusterHost): Result<Unit> =
        runCatching {
            log.info { "Removing sidecar DaemonSet from K3s" }
            k8sService
                .deleteResourcesByLabel(
                    controlHost,
                    Constants.K8s.NAMESPACE,
                    APP_LABEL_KEY,
                    listOf(SidecarManifestBuilder.APP_LABEL),
                ).getOrThrow()
            log.info { "Sidecar DaemonSet removed" }
        }

    override fun restart(controlHost: ClusterHost): Result<Unit> =
        runCatching {
            log.info { "Rolling restart of sidecar DaemonSet" }
            k8sService
                .rolloutRestartDaemonSet(controlHost, SidecarManifestBuilder.APP_LABEL, Constants.K8s.NAMESPACE)
                .getOrThrow()
            log.info { "Sidecar DaemonSet rolling restart triggered" }
        }

    private fun isEcrImage(image: String): Boolean = ".dkr.ecr." in image && ".amazonaws.com" in image

    private fun createEcrPullSecret(
        controlHost: ClusterHost,
        image: String,
    ) {
        log.info { "Getting ECR authorization token for image=$image" }
        val registry = image.substringBefore("/")
        val authToken =
            ecrClient
                .getAuthorizationToken()
                .authorizationData()
                .first()
                .authorizationToken()
        val password = String(Base64.getDecoder().decode(authToken)).substringAfter(":")

        val dockerConfigJson =
            Json.encodeToString(
                DockerConfig(
                    auths =
                        mapOf(
                            registry to DockerConfigAuth(username = "AWS", password = password, auth = authToken),
                        ),
                ),
            )
        val encodedConfig = Base64.getEncoder().encodeToString(dockerConfigJson.toByteArray())

        val secret =
            SecretBuilder()
                .withNewMetadata()
                .withName(ECR_PULL_SECRET_NAME)
                .withNamespace(Constants.K8s.NAMESPACE)
                .endMetadata()
                .withType("kubernetes.io/dockerconfigjson")
                .addToData(".dockerconfigjson", encodedConfig)
                .build()

        k8sService.applyResource(controlHost, secret).getOrThrow()
        log.info { "ECR pull secret created" }
    }
}
