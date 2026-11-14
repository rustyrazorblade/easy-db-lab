package com.rustyrazorblade.easydblab.configuration.otel

import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * A CA certificate in PEM form, for the service-account directory a collector container needs.
 *
 * The core collector's `kubelet_stats` receiver loads the in-cluster CA (`ca.crt`) when it
 * starts, so a collector run outside Kubernetes needs a parseable one. Any real CA will do: this
 * takes the first from the JDK's own truststore, so no fixture or extra dependency is needed.
 */
object ServiceAccountCa {
    private const val PEM_LINE = 64

    /** The first CA certificate in the running JDK's `cacerts`, PEM-encoded. */
    fun pem(): String {
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        File(System.getProperty("java.home"), "lib/security/cacerts").inputStream().use { store.load(it, null) }
        val certificate =
            store.aliases().asSequence().firstNotNullOf { store.getCertificate(it) as? X509Certificate }
        val body = Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(certificate.encoded)
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
    }
}
