package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
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
 * Grants a workload permission to pull an image from the account's ECR.
 *
 * The nodes' instance profile already carries the ECR read permissions, but containerd cannot use
 * an IAM role directly — it needs registry credentials. This turns the role into exactly that: a
 * short-lived ECR authorization token, published as a `kubernetes.io/dockerconfigjson` secret the
 * pod spec references.
 *
 * Shared by every workload that may run a custom image, rather than reimplemented per workload.
 * Credential handling that exists in two places drifts in one of them, and the failure — an image
 * that will not pull — surfaces far from the code that caused it.
 */
class EcrPullSecretService(
    private val k8sService: K8sService,
    private val ecrClient: EcrClient,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Whether this image comes from ECR and therefore needs a pull secret. A public image, or one
     * on a registry the node can already read, needs nothing.
     */
    fun isEcrImage(image: String): Boolean = ".dkr.ecr." in image && ".amazonaws.com" in image

    /**
     * Ensures a usable pull secret exists in [namespace] for [image], returning the secret's name,
     * or an empty string when the image needs none.
     *
     * The secret is rewritten on every call rather than created once: an ECR token expires after
     * twelve hours, so a secret left from an earlier run is as good as absent, and the failure it
     * produces looks like a missing image rather than an expired credential.
     *
     * @return the secret name to attach to the pod's `imagePullSecrets`, or "" when not applicable
     */
    fun ensureFor(
        controlHost: ClusterHost,
        image: String,
        namespace: String,
    ): String {
        if (!isEcrImage(image)) return ""

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
                    auths = mapOf(registry to DockerConfigAuth(username = "AWS", password = password, auth = authToken)),
                ),
            )

        val secret =
            SecretBuilder()
                .withNewMetadata()
                .withName(SECRET_NAME)
                .withNamespace(namespace)
                .endMetadata()
                .withType("kubernetes.io/dockerconfigjson")
                .addToData(".dockerconfigjson", Base64.getEncoder().encodeToString(dockerConfigJson.toByteArray()))
                .build()

        k8sService.applyResource(controlHost, secret).getOrThrow()
        log.info { "ECR pull secret ready in namespace=$namespace" }
        return SECRET_NAME
    }

    companion object {
        /** Name of the secret in whichever namespace it is written to. */
        const val SECRET_NAME = "ecr-pull-secret"
    }
}
