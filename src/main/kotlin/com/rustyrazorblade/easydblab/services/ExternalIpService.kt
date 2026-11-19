package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.network.Ipv4Cidr
import java.net.URI
import java.net.URL

/**
 * Resolves the public IP address of the machine running easy-db-lab.
 *
 * Used to scope security-group SSH ingress to the developer's own address (a `/32`) rather than
 * opening it to the world. This keeps managed-account governance tools (e.g. CloudCustodian, which
 * auto-revokes `0.0.0.0/0` SSH rules) from stripping the rule out from under a Packer build, and is
 * more secure regardless. Both the cluster (`Up`) and Packer build paths resolve their IP here so
 * there is a single implementation.
 */
interface ExternalIpService {
    /** Returns the public IP address as seen from the internet (e.g. "203.0.113.10"). */
    fun getExternalIpAddress(): String

    /** Returns the public IP as a single-host CIDR suitable for a security-group rule (e.g. "203.0.113.10/32"). */
    fun getExternalCidr(): String = "${getExternalIpAddress()}/32"
}

/**
 * Default [ExternalIpService] that asks a list of plain-text public IP services in order.
 *
 * The default order is checkip.amazonaws.com, then api.ipify.org.  A service that fails, or that
 * answers with anything other than a bare IPv4 address once whitespace is trimmed (checkip ends
 * its answer with a newline), is skipped for the next one.  When every service fails, the last
 * failure is thrown with the earlier ones attached as suppressed exceptions; a thrown fetch error
 * (for example a [java.net.SocketTimeoutException]) is rethrown as is.
 *
 * Both the connect and the read of the default fetch are bounded, so a service that accepts the
 * connection and then sends nothing fails instead of waiting without end.  [fetch] is a parameter
 * so a test can decide each answer without the network.
 */
class DefaultExternalIpService(
    private val urls: List<URL> = DEFAULT_URLS,
    private val connectTimeoutMs: Int = Constants.Network.EXTERNAL_IP_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = Constants.Network.EXTERNAL_IP_READ_TIMEOUT_MS,
    private val fetch: (URL) -> String = { url -> readBounded(url, connectTimeoutMs, readTimeoutMs) },
) : ExternalIpService {
    init {
        require(urls.isNotEmpty()) { "At least one public IP service URL is required" }
    }

    override fun getExternalIpAddress(): String {
        val failures = mutableListOf<Throwable>()
        for (url in urls) {
            ask(url)
                .onSuccess { return it }
                .onFailure { failure ->
                    if (failure is InterruptedException) throw failure
                    failures.add(failure)
                }
        }
        val last = failures.last()
        failures.dropLast(1).forEach { last.addSuppressed(it) }
        throw last
    }

    private fun ask(url: URL): Result<String> =
        runCatching { fetch(url) }.mapCatching { answer ->
            val address = answer.trim()
            check(isBareIpv4(address)) { "Public IP service $url answered '$address', which is not an IPv4 address" }
            address
        }

    private companion object {
        val DEFAULT_URLS =
            listOf(Constants.Network.EXTERNAL_IP_URL, Constants.Network.EXTERNAL_IP_FALLBACK_URL)
                .map { URI(it).toURL() }

        fun isBareIpv4(value: String): Boolean = '/' !in value && Ipv4Cidr.parse(value).isSuccess

        fun readBounded(
            url: URL,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): String {
            val connection = url.openConnection()
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            return connection.getInputStream().bufferedReader().use { it.readText() }
        }
    }
}
