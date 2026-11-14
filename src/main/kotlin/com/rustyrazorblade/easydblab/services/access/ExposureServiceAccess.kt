package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.configuration.Exposure
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * The [ServiceAccess] a command gets: the implementation its cluster's recorded [Exposure] names.
 *
 * The exposure is read on every [open], not when Koin builds this, because commands such as `init`
 * resolve their dependencies before a cluster state exists, and because a REPL or server can outlive
 * an `init` that records another exposure. The implementation is kept while the exposure is
 * unchanged, so its forwards are reused; when it changes, the old one is closed and the new one
 * built. [close] closes whichever is held.
 *
 * @param exposure the cluster's recorded exposure
 * @param implementations a builder for each exposure's implementation
 */
class ExposureServiceAccess(
    private val exposure: () -> Exposure,
    private val implementations: Map<Exposure, () -> ServiceAccess>,
) : ServiceAccess {
    private val log = KotlinLogging.logger {}
    private var chosen: Pair<Exposure, ServiceAccess>? = null

    /**
     * Opens [target] on the implementation the exposure selects. Only the selection is locked: an
     * open can wait on the cluster (a port-forward resolving its pod), and the implementations
     * guard their own state, so one slow open never holds up another caller's.
     */
    override fun open(target: ServiceTarget): ServiceEndpoint =
        synchronized(this) { selected() }.open(target).also { endpoint ->
            log.debug { "Reaching ${target.namespace}/${target.service}:${target.servicePort} at $endpoint" }
        }

    @Synchronized
    override fun close() {
        chosen?.second?.close()
        chosen = null
    }

    private fun selected(): ServiceAccess {
        val recorded = exposure()
        chosen?.let { (held, access) ->
            if (held == recorded) return access
            log.debug { "Exposure changed from $held to $recorded; closing ${access::class.simpleName}" }
            access.close()
            chosen = null
        }
        val build = implementations[recorded] ?: error("No service access is registered for exposure $recorded")
        return build().also {
            log.debug { "Service access for exposure $recorded: ${it::class.simpleName}" }
            chosen = recorded to it
        }
    }
}
