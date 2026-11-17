package com.rustyrazorblade.easydblab.commands.metrics

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

/**
 * An exclusive lock, shared by every JVM of this user on this machine, on a test's use of one fixed
 * loopback port (see [acquire]).
 *
 * A test that binds a fixed port, or starts a process that connects to one, shares that port with
 * every other test run on the machine: another worktree's build can run the same test class at the
 * same time.  Holding this lock for the whole test keeps the port to one test at a time, so a fake
 * server sees only its own client, and a client reaches only its own fake server.  The OS frees the
 * lock when the JVM ends, so a killed test run never leaves it held.
 */
internal class MachinePortLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        lock.release()
        channel.close()
    }

    companion object {
        /** Waits until no other test on this machine holds [port], then holds it. */
        fun acquire(port: Int): MachinePortLock {
            val file = File(System.getProperty("java.io.tmpdir"), "easy-db-lab-test-port-$port.lock")
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            return MachinePortLock(channel, channel.lock())
        }
    }
}
