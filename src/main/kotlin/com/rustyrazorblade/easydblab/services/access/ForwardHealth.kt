package com.rustyrazorblade.easydblab.services.access

import io.fabric8.kubernetes.client.LocalPortForward

/**
 * Whether a held port-forward can still carry connections.
 *
 * Fabric8's [LocalPortForward.isAlive] goes false only on `close()` or when the local accept fails.
 * A forward whose pod was replaced keeps `isAlive` true while every connection through it fails, and
 * only [LocalPortForward.errorOccurred] shows it, so a holder must check both before reusing one.
 */
internal val LocalPortForward.isUsable: Boolean
    get() = isAlive && !errorOccurred()

/** The first error the forward recorded, client side or server side, or null when it has none. */
internal val LocalPortForward.firstError: Throwable?
    get() = (clientThrowables + serverThrowables).firstOrNull()
