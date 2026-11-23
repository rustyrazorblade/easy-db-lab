package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A script for one fake call: it may throw, block, or end the instance; it gets the host and the attempt, from 1. */
typealias FakeCall = (host: ClusterHost, attempt: Int) -> Unit

/**
 * The kinds of fault a test throws outside any step. Kotlin code may throw a checked exception as
 * freely as an unchecked one, so the bring-up must treat both the same way.
 */
enum class FaultKind(
    val make: (message: String) -> Exception,
) {
    UNCHECKED(::IllegalStateException),
    CHECKED(::IOException),
}

/**
 * The provider's view of each instance for the bring-up tests: every watched instance runs at
 * once, at the [addresses] it is given (by default `pub-<id>`), and passes its checks, unless its
 * ID is in [held]. [end] ends one the way the provider would report it.
 */
class FakeInstanceTracker(
    private val addresses: (instanceId: String) -> InstanceAddresses = { InstanceAddresses("pub-$it", null) },
) : InstanceTracker {
    private val watches = ConcurrentHashMap<String, InstanceWatch>()
    val held: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    val started = AtomicBoolean(false)

    /** Runs before each watch; a test throws from it to fault a pipeline outside any step. */
    @Volatile var failWatch: (instanceId: String) -> Unit = {}

    override fun watch(instanceId: String): InstanceWatch {
        failWatch(instanceId)
        return watches.computeIfAbsent(instanceId) { id ->
            InstanceWatch(id).also {
                if (id !in held) {
                    it.markRunning(addresses(id))
                    it.markStatusOk()
                }
            }
        }
    }

    /** The instance IDs a pipeline watched, and those it stopped watching. */
    val watched: Set<String> get() = watches.keys.toSet()
    val unwatched: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    override fun unwatch(instanceId: String) {
        unwatched += instanceId
    }

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

    /** The started agents of these aliases never register. */
    val neverRegister: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** While set, no started agent registers. */
    val holdRegistration = AtomicBoolean(false)
    val cleaned: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The aliases whose cleanup fails, with the reason it fails with. */
    val cleanupFailures: MutableMap<String, String> = ConcurrentHashMap()

    /** How many times an alias's cleanup fails before it succeeds. */
    val cleanupFlakes: MutableMap<String, Int> = ConcurrentHashMap()
    val registered: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val watches = ConcurrentHashMap<String, NodeWatch>()
    private val joining = AtomicInteger()
    private val peakJoining = AtomicInteger()
    val closed = AtomicBoolean(false)

    val maxJoining: Int get() = peakJoining.get()

    /** The nodes joined and not yet registered now. */
    val joiningNow: Int get() = joining.get()

    override val reader: ControlPlaneReader get() = this
    override val sampler: HealthSampler = HealthSampler { if (unhealthy.get()) SLOW else HEALTHY }
    override val nodes: NodeTracker get() = this
    override val recovery: String = RECOVERY

    override fun cleanup(alias: String) {
        cleanupFailures[alias]?.let { error(it) }
        if (cleanupFlakes.merge(alias, -1, Int::plus)?.let { it >= 0 } == true) error("transient cleanup failure for $alias")
        cleaned += alias
    }

    override fun close() = closed.set(true)

    override fun watch(
        alias: String,
        privateIp: String,
    ): NodeWatch {
        failWatch(alias)
        return NodeWatch(alias, privateIp).also { watches[alias] = it }
    }

    /** Runs before each watch; a test throws from it to fault the coordinator outside any step. */
    var failWatch: (alias: String) -> Unit = {}

    override fun unwatch(alias: String) {
        watches.remove(alias)
    }

    /** A node's agent was started: it registers after the delay, from its own thread, as a real one does. */
    fun joined(alias: String) {
        if (alias in neverRegister) return
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

    override fun networkConditions(): Map<String, Boolean> = mapOf("network ready" to open.get())

    companion object {
        const val RECOVERY = "Restart the fake control plane"
        val HEALTHY = ControlPlaneHealth.Sample.Reading(latencyMs = 1, rejections = 0)
        val SLOW = ControlPlaneHealth.Sample.Reading(latencyMs = 60_000, rejections = 0)
    }
}

/**
 * Every node and control step for the bring-up tests. Each call is logged in order as
 * `<alias> <call> <instanceId>`; a script in [scripts] under `<alias> <call>` runs first and may
 * throw, block or end the instance. [imageDefects] gives what an alias's image lacks.
 *
 * Every call that runs over SSH in production is counted while it runs: [sshPeak] is the most
 * at once and [sshInFlight] the count now. Each one holds its session for [sshHoldMs].
 */
class FakeNodeSteps(
    private val controlPlane: FakeControlPlane,
) : NodeSteps,
    ControlNodeSteps {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val scripts: MutableMap<String, FakeCall> = ConcurrentHashMap()
    val imageDefects: MutableMap<String, ImageDefect> = ConcurrentHashMap()
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()
    val lastAccess = AtomicReference<ControlPlaneAccess>()
    var sshHoldMs = 0L
    private val inFlight = AtomicInteger()
    private val peak = AtomicInteger()

    val sshInFlight: Int get() = inFlight.get()
    val sshPeak: Int get() = peak.get()

    private fun call(
        name: String,
        host: ClusterHost,
    ) {
        calls += "${host.alias} $name ${host.instanceId}"
        val attempt = attempts.computeIfAbsent("${host.instanceId} $name") { AtomicInteger() }.incrementAndGet()
        scripts["${host.alias} $name"]?.invoke(host, attempt)
    }

    /** A call that holds an SSH session in production. */
    private fun sshCall(
        name: String,
        host: ClusterHost,
    ) {
        peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
        try {
            if (sshHoldMs > 0) Thread.sleep(sshHoldMs)
            call(name, host)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** The calls named [name], as `<alias> <instanceId>`, in order. */
    fun callsOf(name: String): List<String> =
        synchronized(calls) { calls.toList() }
            .map { it.split(" ") }
            .filter { it[1] == name }
            .map { "${it[0]} ${it[2]}" }

    override fun sshAnswers(host: ClusterHost): Boolean {
        sshCall("ssh", host)
        return true
    }

    override fun setup(
        serverType: ServerType,
        host: ClusterHost,
    ) = sshCall("setup", host)

    override fun checkImage(host: ClusterHost): ImageDefect? {
        sshCall("image", host)
        return imageDefects[host.alias]
    }

    override fun configureRegistryTls(host: ClusterHost) = sshCall("registry-tls", host)

    override fun join(
        host: ClusterHost,
        serverType: ServerType,
        access: ControlPlaneAccess,
        attempt: Int,
    ) {
        sshCall("join", host)
        lastAccess.set(access)
        controlPlane.joined(host.alias)
    }

    /** What [joinLog] returns for an alias; a missing alias reads as an empty log. */
    val agentLogs: MutableMap<String, String> = ConcurrentHashMap()

    override fun joinLog(host: ClusterHost): String {
        sshCall("join-log", host)
        return agentLogs[host.alias].orEmpty()
    }

    private lateinit var control: ClusterHost

    override fun writeWorkspace() {
        calls += "control workspace -"
    }

    override fun connect(control: ClusterHost) {
        this.control = control
        call("connect", control)
    }

    override fun prepareRegistry(control: ClusterHost) = sshCall("registry-cert", control)

    override fun startControlPlane(control: ClusterHost): ControlPlaneAccess {
        sshCall("control-plane", control)
        return ControlPlaneAccess("https://${control.privateIp}:6443", "secret-token")
    }

    override fun openControlPlane(): ControlPlaneConnection {
        call("open", control)
        return controlPlane
    }

    override fun configureControlPlane(control: ClusterHost) = sshCall("cni", control)
}
