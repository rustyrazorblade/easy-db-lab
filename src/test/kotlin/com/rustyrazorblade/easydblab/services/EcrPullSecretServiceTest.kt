package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import io.fabric8.kubernetes.api.model.Secret
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.ecr.EcrClient
import software.amazon.awssdk.services.ecr.model.AuthorizationData
import software.amazon.awssdk.services.ecr.model.GetAuthorizationTokenResponse
import java.util.Base64

class EcrPullSecretServiceTest {
    private val controlHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.1.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control0",
        )

    private val ecrImage = "102382809497.dkr.ecr.us-west-2.amazonaws.com/rustyrazorblade/cassandra-easy-stress:mybranch"

    @Test
    fun `an ECR image is recognised`() {
        assertThat(service().isEcrImage(ecrImage)).isTrue()
    }

    @Test
    fun `images on other registries are not mistaken for ECR`() {
        val svc = service()

        assertThat(svc.isEcrImage("ghcr.io/apache/cassandra-easy-stress:latest")).isFalse()
        assertThat(svc.isEcrImage("otel/opentelemetry-collector-contrib:latest")).isFalse()
        // Looks ECR-ish but is not: a registry named to resemble one must not get AWS credentials.
        assertThat(svc.isEcrImage("dkr.ecr.example.com/thing:1")).isFalse()
        assertThat(svc.isEcrImage("myrepo.amazonaws.com.evil.test/thing:1")).isFalse()
    }

    @Test
    fun `a non-ECR image needs no secret and costs no AWS call`() {
        val ecr = mock<EcrClient>()
        val k8s = mock<K8sService>()

        val name = EcrPullSecretService(k8s, ecr).ensureFor(controlHost, "ghcr.io/apache/x:latest", "default")

        assertThat(name).isEmpty()
        verify(ecr, never()).getAuthorizationToken()
        verify(k8s, never()).applyResource(any(), any())
    }

    @Test
    fun `an ECR image yields a dockerconfigjson secret the pod can use`() {
        val k8s = mock<K8sService>()
        whenever(k8s.applyResource(any(), any())).thenReturn(Result.success(Unit))
        val captor = argumentCaptor<Secret>()

        val name = EcrPullSecretService(k8s, ecrClientReturning("AWS:s3cr3t")).ensureFor(controlHost, ecrImage, "default")

        assertThat(name).isEqualTo(EcrPullSecretService.SECRET_NAME)
        verify(k8s).applyResource(eq(controlHost), captor.capture())

        val secret = captor.firstValue
        assertThat(secret.type).isEqualTo("kubernetes.io/dockerconfigjson")
        assertThat(secret.metadata.namespace).isEqualTo("default")

        val config =
            Json
                .parseToJsonElement(String(Base64.getDecoder().decode(secret.data[".dockerconfigjson"])))
                .jsonObject["auths"]!!
                .jsonObject
        // Keyed by registry host only: a key carrying the image path would not match on pull.
        val registry = "102382809497.dkr.ecr.us-west-2.amazonaws.com"
        assertThat(config.keys).containsExactly(registry)
        assertThat(config[registry]!!.jsonObject["username"]!!.jsonPrimitive.content).isEqualTo("AWS")
        // The password is the half after the colon, not the whole decoded token.
        assertThat(config[registry]!!.jsonObject["password"]!!.jsonPrimitive.content).isEqualTo("s3cr3t")
    }

    @Test
    fun `a password containing a colon survives intact`() {
        val k8s = mock<K8sService>()
        whenever(k8s.applyResource(any(), any())).thenReturn(Result.success(Unit))
        val captor = argumentCaptor<Secret>()

        EcrPullSecretService(k8s, ecrClientReturning("AWS:pa:ss:word")).ensureFor(controlHost, ecrImage, "default")

        verify(k8s).applyResource(any(), captor.capture())
        val config =
            Json
                .parseToJsonElement(String(Base64.getDecoder().decode(captor.firstValue.data[".dockerconfigjson"])))
                .jsonObject["auths"]!!
                .jsonObject
                .values
                .first()
                .jsonObject
        assertThat(config["password"]!!.jsonPrimitive.content).isEqualTo("pa:ss:word")
    }

    @Test
    fun `the secret is written to the namespace it was asked for`() {
        val k8s = mock<K8sService>()
        whenever(k8s.applyResource(any(), any())).thenReturn(Result.success(Unit))
        val captor = argumentCaptor<Secret>()

        EcrPullSecretService(k8s, ecrClientReturning("AWS:x")).ensureFor(controlHost, ecrImage, "observability")

        verify(k8s).applyResource(any(), captor.capture())
        assertThat(captor.firstValue.metadata.namespace).isEqualTo("observability")
    }

    private fun service() = EcrPullSecretService(mock(), mock())

    private fun ecrClientReturning(decodedToken: String): EcrClient {
        val encoded = Base64.getEncoder().encodeToString(decodedToken.toByteArray())
        val ecr = mock<EcrClient>()
        whenever(ecr.getAuthorizationToken()).thenReturn(
            GetAuthorizationTokenResponse
                .builder()
                .authorizationData(AuthorizationData.builder().authorizationToken(encoded).build())
                .build(),
        )
        return ecr
    }
}
