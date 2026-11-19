package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The cloud provider for the bring-up tests (design D11): every call is recorded in order in [calls], a call
 * named in [failing] throws, and [maxInFlight] is the most calls that ever ran at once. Launched
 * instances get IDs `i-<alias>-<n>`, one IP per launch, in the zone the ordinal lands in.
 */
class FakeInstanceFleet(
    private val zones: List<String> = listOf("us-west-2a", "us-west-2b", "us-west-2c"),
) : InstanceFleet {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val failing: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val launched = AtomicInteger()
    private val inFlight = AtomicInteger()
    private val peak = AtomicInteger()

    /** The most fleet calls that were ever in progress together. */
    val maxInFlight: Int get() = peak.get()

    private fun <T> record(
        call: String,
        block: () -> T,
    ): T {
        peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
        try {
            calls += call
            // Give another thread the chance to overlap, so serialisation is actually tested.
            Thread.sleep(1)
            check(failing.none { call.startsWith(it) }) { "$call refused" }
            return block()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** Runs inside each terminate, before it returns; a test blocks here to hold the replacer lock. */
    @Volatile var onTerminate: (String) -> Unit = {}

    override fun terminate(instanceId: String) = record("terminate $instanceId") { onTerminate(instanceId) }

    override fun verifyReplacement(
        serverType: ServerType,
        ordinal: Int,
    ) = record("verify ${serverType.serverType}$ordinal") { }

    override fun launch(
        serverType: ServerType,
        ordinal: Int,
    ): ClusterHost =
        record("launch ${serverType.serverType}$ordinal") {
            val n = launched.incrementAndGet()
            val alias = "${serverType.serverType}$ordinal"
            ClusterHost(
                publicIp = "54.0.1.$n",
                privateIp = "10.0.1.$n",
                alias = alias,
                availabilityZone = zones[ordinal % zones.size],
                instanceId = "i-$alias-$n",
            )
        }
}
