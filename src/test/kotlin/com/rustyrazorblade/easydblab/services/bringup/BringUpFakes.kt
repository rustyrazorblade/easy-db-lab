package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A script for one fake call: it may throw, block, or end the instance; it gets the host and the attempt, from 1. */
typealias FakeCall = (host: ClusterHost, attempt: Int) -> Unit

/**
 * The provider's view of each instance for the bring-up tests: every watched instance runs at
 * once, at `pub-<id>`, and passes its checks, unless its ID is in [held]. [end] ends one the way
 * the provider would report it.
 */
class FakeInstanceTracker : InstanceTracker {
    private val watches = ConcurrentHashMap<String, InstanceWatch>()
    val held: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    val started = AtomicBoolean(false)

    override fun watch(instanceId: String): InstanceWatch =
        watches.computeIfAbsent(instanceId) { id ->
            InstanceWatch(id).also {
                if (id !in held) {
                    it.markRunning(InstanceAddresses("pub-$id", null))
                    it.markStatusOk()
                }
            }
        }

    override fun unwatch(instanceId: String) = Unit

    override fun start(): AutoCloseable {
        started.set(true)
        return AutoCloseable { started.set(false) }
    }

    /** The provider ended [instanceId]. */
    fun end(instanceId: String) = watch(instanceId).end("terminated")
}

/**
 * The control plane for the bring-up tests. The gate's conditions hold once [open] is set. A
 * node registers [registrationDelayMs] after its join call, and is Ready at once unless
 * [autoReady] is off, when [makeReady] does it. [maxJoining] is the most nodes ever joined and
 * not yet registered. Health samples are healthy unless [unhealthy] is set.
 */
class FakeControlPlane(
    open: Boolean = true,
) : ControlPlaneConnection,
    NodeTracker,
    ControlPlaneReader {
    val open = AtomicBoolean(open)
    var autoReady = true
    var registrationDelayMs = 0L

    /** Nodes at these private IPs never become Ready. */
    val neverReady: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** While set, every sample is past the latency limit. */
    val unhealthy = AtomicBoolean(false)

    /** While set, no started agent registers. */
    val holdRegistration = AtomicBoolean(false)
    val cleaned: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val registered: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val watches = ConcurrentHashMap<String, NodeWatch>()
    private val joining = AtomicInteger()
    private val peakJoining = AtomicInteger()
    val closed = AtomicBoolean(false)

    val maxJoining: Int get() = peakJoining.get()

    override val reader: ControlPlaneReader get() = this
    override val sampler: HealthSampler = HealthSampler { if (unhealthy.get()) SLOW else HEALTHY }
    override val nodes: NodeTracker get() = this

    override fun cleanup(alias: String) {
        cleaned += alias
    }

    override fun close() = closed.set(true)

    override fun watch(
        alias: String,
        privateIp: String,
    ): NodeWatch = NodeWatch(alias, privateIp).also { watches[alias] = it }

    override fun unwatch(alias: String) {
        watches.remove(alias)
    }

    /** A node's agent was started: it registers after the delay, from its own thread, as a real one does. */
    fun joined(alias: String) {
        peakJoining.accumulateAndGet(joining.incrementAndGet(), ::maxOf)
        Thread.ofVirtual().start {
            Thread.sleep(registrationDelayMs)
            while (holdRegistration.get()) Thread.sleep(1)
            joining.decrementAndGet()
            registered += alias
            watches[alias]?.let { watch ->
                watch.registered.complete(Unit)
                if (autoReady && watch.privateIp !in neverReady) watch.ready.complete(Unit)
            }
        }
    }

    /** [alias] becomes Ready. */
    fun makeReady(alias: String) {
        watches[alias]?.ready?.complete(Unit)
    }

    override fun apiServerReady(): Boolean = open.get()

    override fun nodeRegistered(alias: String): Boolean = open.get()

    override fun operatorReplicas(): ControlPlaneReader.Replicas? = if (open.get()) ControlPlaneReader.Replicas(1, 1) else null

    override fun establishedCrds(names: List<String>): Set<String> = if (open.get()) names.toSet() else emptySet()

    companion object {
        val HEALTHY = ControlPlaneHealth.Sample.Reading(latencyMs = 1, rejections = 0)
        val SLOW = ControlPlaneHealth.Sample.Reading(latencyMs = 60_000, rejections = 0)
    }
}

/**
 * Every node and control step for the bring-up tests. Each call is logged in order as
 * `<alias> <call> <instanceId>`; a script in [scripts] under `<alias> <call>` runs first and may
 * throw, block or end the instance. [missingFixes] gives an alias's missing image files.
 */
class FakeNodeSteps(
    private val controlPlane: FakeControlPlane,
) : NodeSteps,
    ControlNodeSteps {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val scripts: MutableMap<String, FakeCall> = ConcurrentHashMap()
    val missingFixes: MutableMap<String, List<String>> = ConcurrentHashMap()
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()
    val lastAccess = AtomicReference<ControlPlaneAccess>()

    private fun call(
        name: String,
        host: ClusterHost,
    ) {
        calls += "${host.alias} $name ${host.instanceId}"
        val attempt = attempts.computeIfAbsent("${host.instanceId} $name") { AtomicInteger() }.incrementAndGet()
        scripts["${host.alias} $name"]?.invoke(host, attempt)
    }

    /** The calls named [name], as `<alias> <instanceId>`, in order. */
    fun callsOf(name: String): List<String> =
        synchronized(calls) { calls.toList() }
            .map { it.split(" ") }
            .filter { it[1] == name }
            .map { "${it[0]} ${it[2]}" }

    override fun sshAnswers(host: ClusterHost): Boolean {
        call("ssh", host)
        return true
    }

    override fun setup(
        serverType: ServerType,
        host: ClusterHost,
    ) = call("setup", host)

    override fun downloadVersions(host: ClusterHost) = call("versions", host)

    override fun missingImageFixes(host: ClusterHost): List<String> {
        call("image", host)
        return missingFixes[host.alias].orEmpty()
    }

    override fun configureRegistryTls(host: ClusterHost) = call("registry-tls", host)

    override fun join(
        host: ClusterHost,
        serverType: ServerType,
        access: ControlPlaneAccess,
        attempt: Int,
    ) {
        call("join", host)
        lastAccess.set(access)
        controlPlane.joined(host.alias)
    }

    private lateinit var control: ClusterHost

    override fun writeWorkspace() {
        calls += "control workspace -"
    }

    override fun connect(control: ClusterHost) {
        this.control = control
        call("connect", control)
    }

    override fun prepareRegistry(control: ClusterHost) = call("registry-cert", control)

    override fun startControlPlane(control: ClusterHost): ControlPlaneAccess {
        call("k3s-server", control)
        return ControlPlaneAccess("https://${control.privateIp}:6443", "secret-token")
    }

    override fun openControlPlane(): ControlPlaneConnection {
        call("open", control)
        return controlPlane
    }

    override fun configureControlPlane(control: ClusterHost) = call("cni", control)
}
