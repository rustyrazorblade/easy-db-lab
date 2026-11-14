package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.services.access.ServiceTarget
import java.time.Duration

/**
 * An [ObservabilityHttp] that records each request and answers from a queue, for testing the code
 * that builds requests and reads responses without a network. Unanswered requests get a 200 with an
 * empty body.
 */
class RecordingObservabilityHttp(
    vararg answers: ObservabilityResponse,
) : ObservabilityHttp {
    /** One recorded request. */
    data class Call(
        val method: String,
        val port: Int,
        val path: String,
        val body: String,
        val timeout: Duration,
    )

    private val pending = ArrayDeque(answers.toList())
    val calls = mutableListOf<Call>()

    override fun get(
        target: ServiceTarget,
        pathAndQuery: String,
        timeout: Duration,
    ): ObservabilityResponse = answer(Call("GET", target.servicePort, pathAndQuery, "", timeout))

    override fun post(
        target: ServiceTarget,
        path: String,
        body: String,
        contentType: String,
        timeout: Duration,
    ): ObservabilityResponse = answer(Call("POST", target.servicePort, path, body, timeout))

    private fun answer(call: Call): ObservabilityResponse {
        calls.add(call)
        return pending.removeFirstOrNull() ?: ObservabilityResponse(200, "")
    }
}
