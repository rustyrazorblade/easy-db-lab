package com.rustyrazorblade.easydblab.configuration.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Every [JavaAgent] image exists in its registry, so a pod's agent init container can pull it on any
 * cluster.
 *
 * A local `docker pull` cannot show this: other integration tests build the Pyroscope agent image
 * from its Dockerfile under the published tag, so the host's Docker cache can hold an image the
 * registry never received. This asks the registry itself for the tag's manifest, over the OCI
 * distribution API, with the anonymous pull token a public image needs.
 */
class JavaAgentImagesPublishedIntegrationTest {
    private val http =
        HttpClient
            .newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    @ParameterizedTest
    @EnumSource(JavaAgent::class)
    fun `the agent image is published`(agent: JavaAgent) {
        val image = ImageReference.parse(agent.image)

        val status = manifestStatus(image)

        assertThat(status)
            .describedAs(
                "${agent.image} is not in its registry (HTTP $status). A pod that loads the ${agent.name} agent cannot start " +
                    "until it is published; the Pyroscope image is published by the publish-pyroscope-agent job " +
                    "of .github/workflows/publish-container.yml.",
            ).isEqualTo(HTTP_OK)
    }

    /** The status of a HEAD on the tag's manifest, after the anonymous token exchange when the registry asks for one. */
    private fun manifestStatus(image: ImageReference): Int {
        val first = headManifest(image, token = null)
        if (first.statusCode() != HTTP_UNAUTHORIZED) return first.statusCode()
        val challenge =
            first.headers().firstValue("WWW-Authenticate").orElseThrow {
                AssertionError("${image.registry} answered 401 with no WWW-Authenticate challenge")
            }
        return headManifest(image, anonymousToken(challenge, image)).statusCode()
    }

    private fun headManifest(
        image: ImageReference,
        token: String?,
    ): HttpResponse<Void> {
        val request =
            HttpRequest
                .newBuilder(URI("https://${image.registry}/v2/${image.repository}/manifests/${image.tag}"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("Accept", MANIFEST_TYPES)
                .timeout(TIMEOUT)
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .build()
        return http.send(request, HttpResponse.BodyHandlers.discarding())
    }

    /** The token the challenge's realm hands out for pulling [image] without credentials. */
    private fun anonymousToken(
        challenge: String,
        image: ImageReference,
    ): String {
        val parameters = CHALLENGE_PARAMETER.findAll(challenge).associate { it.groupValues[1] to it.groupValues[2] }
        val realm = checkNotNull(parameters["realm"]) { "No realm in the challenge '$challenge'" }
        val query =
            listOfNotNull(
                parameters["service"]?.let { "service=${encode(it)}" },
                "scope=${encode("repository:${image.repository}:pull")}",
            ).joinToString("&")
        val response =
            http.send(
                HttpRequest.newBuilder(URI("$realm?$query")).timeout(TIMEOUT).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        check(response.statusCode() == HTTP_OK) { "The token request to $realm answered ${response.statusCode()}" }
        val body = Json.parseToJsonElement(response.body()).jsonObject
        return checkNotNull((body["token"] ?: body["access_token"])?.jsonPrimitive?.content) { "No token in $realm's answer" }
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /** An image reference with an explicit registry host and a tag, the form every [JavaAgent] image takes. */
    private data class ImageReference(
        val registry: String,
        val repository: String,
        val tag: String,
    ) {
        companion object {
            fun parse(reference: String): ImageReference {
                val registry = reference.substringBefore('/')
                val rest = reference.substringAfter('/')
                require(registry.contains('.') && rest.contains(':')) { "$reference does not name a registry host and a tag" }
                return ImageReference(registry, rest.substringBeforeLast(':'), rest.substringAfterLast(':'))
            }
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        val TIMEOUT: Duration = Duration.ofSeconds(30)
        val CHALLENGE_PARAMETER = Regex("""(\w+)="([^"]*)"""")
        const val MANIFEST_TYPES =
            "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, " +
                "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json"
    }
}
