package com.rustyrazorblade.easydblab.services.bringup.aws

import com.rustyrazorblade.easydblab.providers.aws.InstanceDetails
import com.rustyrazorblade.easydblab.providers.aws.InstanceId
import com.rustyrazorblade.easydblab.services.aws.InstanceStateReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.concurrent.TimeUnit

class Ec2InstanceStateWatcherTest {
    /** EC2 as a map the test edits: each instance's state and public IP, and who passed status checks. */
    private class FakeEc2(
        override val batchSize: Int = 100,
    ) : InstanceStateReader {
        val states = mutableMapOf<InstanceId, Pair<String, String?>>()
        val statusOk = mutableSetOf<InstanceId>()
        val describeCalls = mutableListOf<List<InstanceId>>()
        val statusCalls = mutableListOf<List<InstanceId>>()

        override fun describeStates(ids: List<InstanceId>): List<InstanceDetails> {
            describeCalls += ids
            return ids.mapNotNull { id ->
                states[id]?.let { (state, ip) -> InstanceDetails(id, state, ip, "10.0.0.1", "us-west-2a") }
            }
        }

        override fun passedStatusChecks(ids: List<InstanceId>): Set<InstanceId> {
            statusCalls += ids
            return ids.filter { it in statusOk }.toSet()
        }

        fun running(
            id: InstanceId,
            ip: String? = "1.2.3.4",
        ) {
            states[id] = "running" to ip
        }
    }

    private val ec2 = FakeEc2()
    private val watcher = Ec2InstanceStateWatcher(ec2, Duration.ZERO)

    @Test
    fun `250 instances are read in requests of at most 100, one of each read per batch`() {
        val ids = (0 until 250).map { "i-$it" }
        ids.forEach { ec2.running(it) }
        ids.forEach { watcher.watch(it) }

        watcher.poll()

        assertThat(ec2.describeCalls.map { it.size }).containsExactly(100, 100, 50)
        assertThat(ec2.statusCalls.map { it.size }).containsExactly(100, 100, 50)
        assertThat(ec2.describeCalls.flatten()).containsExactlyInAnyOrderElementsOf(ids)
    }

    @Test
    fun `running completes only once the instance has a public IP, and status OK only after the checks pass`() {
        val watch = watcher.watch("i-1")
        ec2.states["i-1"] = "pending" to null

        watcher.poll()
        assertThat(watch.running).isNotDone()

        ec2.running("i-1", ip = null)
        watcher.poll()
        assertThat(watch.running).isNotDone()
        assertThat(ec2.statusCalls).isEmpty()

        ec2.running("i-1")
        watcher.poll()
        assertThat(watch.running.get().publicIp).isEqualTo("1.2.3.4")
        assertThat(watch.statusOk).isNotDone()

        ec2.statusOk += "i-1"
        watcher.poll()
        assertThat(watch.statusOk).isDone()
        assertThat(watch.ended).isNotDone()
    }

    @Test
    fun `an instance EC2 does not know yet is not ended`() {
        val watch = watcher.watch("i-new")

        watcher.poll()
        watcher.poll()

        assertThat(watch.ended).isNotDone()
        assertThat(watch.running).isNotDone()
    }

    @ParameterizedTest(name = "ended as {0}")
    @ValueSource(strings = ["shutting-down", "terminated", "stopping", "stopped"])
    fun `an instance that ends after its status checks is still seen, so a later step stops`(state: String) {
        val watch = watcher.watch("i-1")
        ec2.running("i-1")
        ec2.statusOk += "i-1"
        watcher.poll()
        assertThat(watch.statusOk).isDone()

        ec2.states["i-1"] = state to null
        watcher.poll()

        assertThat(watch.ended.get(1, TimeUnit.SECONDS)).isEqualTo(state)
    }

    @Test
    fun `an instance that ends before it runs fails its waits as well as ending`() {
        val watch = watcher.watch("i-1")
        ec2.states["i-1"] = "terminated" to null

        watcher.poll()

        assertThat(watch.ended.get()).isEqualTo("terminated")
        assertThat(watch.running).isCompletedExceptionally()
        assertThat(watch.statusOk).isCompletedExceptionally()
    }

    @Test
    fun `an instance EC2 stops returning after it was seen is ended`() {
        val watch = watcher.watch("i-1")
        ec2.running("i-1")
        watcher.poll()

        ec2.states.remove("i-1")
        watcher.poll()

        assertThat(watch.ended.get()).isEqualTo(Ec2InstanceStateWatcher.DROPPED)
    }

    @Test
    fun `an unwatched instance is no longer read, and its end is not reported`() {
        val watch = watcher.watch("i-old")
        ec2.running("i-old")
        watcher.poll()

        watcher.unwatch("i-old")
        ec2.states["i-old"] = "terminated" to null
        ec2.describeCalls.clear()
        watcher.poll()

        assertThat(ec2.describeCalls.flatten()).doesNotContain("i-old")
        assertThat(watch.ended).isNotDone()
    }

    @Test
    fun `the status read names only running instances that have not passed yet`() {
        ec2.running("i-1")
        ec2.running("i-2")
        ec2.states["i-3"] = "pending" to null
        ec2.statusOk += "i-1"
        listOf("i-1", "i-2", "i-3").forEach { watcher.watch(it) }
        watcher.poll()
        ec2.statusCalls.clear()

        watcher.poll()

        assertThat(ec2.statusCalls.flatten()).containsExactly("i-2")
    }

    @Test
    fun `a failed read does not end anything, and the next poll reads again`() {
        val failing =
            object : InstanceStateReader by ec2 {
                var fail = true

                override fun describeStates(ids: List<InstanceId>): List<InstanceDetails> {
                    if (fail) error("throttled")
                    return ec2.describeStates(ids)
                }
            }
        val watcher = Ec2InstanceStateWatcher(failing, Duration.ZERO)
        val watch = watcher.watch("i-1")
        ec2.running("i-1")

        watcher.poll()
        assertThat(watch.running).isNotDone()
        assertThat(watch.ended).isNotDone()

        failing.fail = false
        watcher.poll()
        assertThat(watch.running).isDone()
    }

    @Test
    fun `the watcher polls on its own thread until closed`() {
        val watch = watcher.watch("i-1")
        ec2.running("i-1")

        watcher.start().use {
            assertThat(watch.running.get(5, TimeUnit.SECONDS).publicIp).isEqualTo("1.2.3.4")
        }
    }
}
