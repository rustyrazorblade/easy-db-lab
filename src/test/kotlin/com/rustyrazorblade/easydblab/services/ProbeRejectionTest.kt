package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProbeRejectionTest {
    @Test
    fun `a Pod Security rejection is a refusal carrying the API server's message`() {
        val message =
            "pods \"edl-capability-probe-privileged\" is forbidden: violates PodSecurity \"baseline:latest\": " +
                "privileged (container \"probe\" must not set securityContext.privileged=true)"

        assertThat(ProbeRejection.classify(403, message)).isEqualTo(ProbeRejection.Refused(message))
    }

    @Test
    fun `a validation rejection is a refusal`() {
        assertThat(ProbeRejection.classify(422, "Pod is invalid")).isEqualTo(ProbeRejection.Refused("Pod is invalid"))
    }

    @Test
    fun `a webhook that does not support dry run is a refusal naming the webhook`() {
        val message = "admission webhook \"validate.kyverno.svc-fail\" does not support dry run"

        assertThat(ProbeRejection.classify(400, message))
            .isEqualTo(ProbeRejection.Refused("admission webhook validate.kyverno.svc-fail does not support dry run, so it cannot be probed"))
    }

    @Test
    fun `any other bad request is not a refusal`() {
        assertThat(ProbeRejection.classify(400, "the server rejected our request for an unknown reason"))
            .isEqualTo(ProbeRejection.Unexpected("the server rejected our request for an unknown reason"))
    }

    @Test
    fun `a not-found or server error is not a refusal`() {
        assertThat(ProbeRejection.classify(404, "namespaces \"x\" not found")).isInstanceOf(ProbeRejection.Unexpected::class.java)
        assertThat(ProbeRejection.classify(500, "etcdserver: request timed out")).isInstanceOf(ProbeRejection.Unexpected::class.java)
    }
}
