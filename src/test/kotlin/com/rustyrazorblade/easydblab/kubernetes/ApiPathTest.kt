package com.rustyrazorblade.easydblab.kubernetes

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The resource, namespace, name and subresource of a Kubernetes API request path. */
class ApiPathTest {
    @Test
    fun `core and grouped paths, namespaced and cluster-scoped, with and without a name`() {
        assertThat(ApiPath.parse("/api/v1/namespaces/lab/pods")).isEqualTo(ApiPath("pods", "lab"))
        assertThat(ApiPath.parse("/api/v1/namespaces/lab/pods/x")).isEqualTo(ApiPath("pods", "lab", "x"))
        assertThat(ApiPath.parse("/api/v1/pods")).isEqualTo(ApiPath("pods"))
        assertThat(ApiPath.parse("/api/v1/nodes/n1")).isEqualTo(ApiPath("nodes", name = "n1"))
        assertThat(ApiPath.parse("/apis/apps/v1/namespaces/lab/deployments")).isEqualTo(ApiPath("deployments", "lab"))
        assertThat(ApiPath.parse("/apis/apps/v1/namespaces/lab/deployments/web")).isEqualTo(ApiPath("deployments", "lab", "web"))
    }

    @Test
    fun `a namespace itself and a subresource`() {
        assertThat(ApiPath.parse("/api/v1/namespaces/lab")).isEqualTo(ApiPath("namespaces", name = "lab"))
        assertThat(ApiPath.parse("/api/v1/namespaces")).isEqualTo(ApiPath("namespaces"))
        assertThat(ApiPath.parse("/api/v1/namespaces/lab/pods/web-0/exec")).isEqualTo(ApiPath("pods/exec", "lab", "web-0"))
    }

    @Test
    fun `a path outside the resource API is not parsed`() {
        assertThat(ApiPath.parse("/version")).isNull()
        assertThat(ApiPath.parse("/api")).isNull()
        assertThat(ApiPath.parse("/healthz")).isNull()
    }
}
