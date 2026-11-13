package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.configuration.Exposure

/**
 * The [ServiceAccess] a command gets: the implementation its cluster's recorded [Exposure] names.
 *
 * The exposure is read on the first [open], not when Koin builds this, because commands such as
 * `init` resolve their dependencies before a cluster state exists. The chosen implementation is kept
 * for the rest of the process and closed by [close].
 *
 * @param exposure the cluster's recorded exposure
 * @param implementations a builder for each exposure's implementation
 */
class ExposureServiceAccess(
    private val exposure: () -> Exposure,
    private val implementations: Map<Exposure, () -> ServiceAccess>,
) : ServiceAccess {
    private var chosen: ServiceAccess? = null

    @Synchronized
    override fun open(target: ServiceTarget): ServiceEndpoint = selected().open(target)

    @Synchronized
    override fun close() {
        chosen?.close()
        chosen = null
    }

    private fun selected(): ServiceAccess =
        chosen ?: run {
            val recorded = exposure()
            val build = implementations[recorded] ?: error("No service access is registered for exposure $recorded")
            build().also { chosen = it }
        }
}
