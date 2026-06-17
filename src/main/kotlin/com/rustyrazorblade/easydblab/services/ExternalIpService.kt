package com.rustyrazorblade.easydblab.services

import java.net.URI

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
 * Default [ExternalIpService] that resolves the public IP via api.ipify.org.
 */
class DefaultExternalIpService : ExternalIpService {
    override fun getExternalIpAddress(): String = URI("https://api.ipify.org/").toURL().readText()
}
