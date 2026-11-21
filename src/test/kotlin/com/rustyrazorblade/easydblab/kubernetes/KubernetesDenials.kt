package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.CommandFailureReporter
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.internal.OperationSupport
import io.fabric8.kubernetes.client.http.StandardHttpRequest
import java.net.URI

/**
 * Test fixture for sites that must keep a Kubernetes denial as their failure's cause: builds a 403
 * the way Fabric8 does, and reports a failure through [CommandFailureReporter] with the real
 * [KubernetesErrorHandler], returning the one event the user would see.
 */
object KubernetesDenials {
    private const val FORBIDDEN = 403

    /** A 403 for a [method] request on [path], built by Fabric8's own [OperationSupport.requestFailure]. */
    fun forbidden(
        method: String,
        path: String,
        message: String,
    ): KubernetesClientException {
        val request = StandardHttpRequest(emptyMap(), URI("https://10.0.0.1:6443$path"), method, null)
        val status =
            StatusBuilder()
                .withStatus("Failure")
                .withCode(FORBIDDEN)
                .withReason("Forbidden")
                .withMessage(message)
                .build()
        return OperationSupport.requestFailure(request, status)
    }

    /** The single event [CommandFailureReporter] emits for [failure]. */
    fun reported(failure: Throwable): Event {
        val emitted = mutableListOf<Event>()
        val bus =
            EventBus().also {
                it.addListener(
                    object : EventListener {
                        override fun onEvent(envelope: EventEnvelope) {
                            emitted.add(envelope.event)
                        }

                        override fun close() = Unit
                    },
                )
            }
        CommandFailureReporter(bus, listOf(KubernetesErrorHandler())).report(failure)
        return emitted.single()
    }
}
