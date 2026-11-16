package com.rustyrazorblade.easydblab.proxy

import java.io.File
import kotlin.system.exitProcess

/**
 * A second process that writes a workspace's [ProxyEnvFile], for the test that two processes writing
 * different keys at once keep each other's values. A JVM's own lock cannot show that, so the test runs
 * this `main` in a child JVM.
 *
 * It records the tunnel ports `1..rounds` in order. After each write it reads the file back, and exits
 * with [LOST_UPDATE] when the port it reads is older than the one it wrote: another writer read the
 * file before this write and then replaced it, and this update was lost.
 *
 * Arguments: the workspace directory and the number of rounds.
 */
internal object ProxyEnvFileWriterMain {
    /** The exit status when a write of this process was lost. */
    const val LOST_UPDATE = 3

    @JvmStatic
    fun main(args: Array<String>) {
        val envFile = ProxyEnvFile(File(args[0]))
        val rounds = args[1].toInt()
        for (port in 1..rounds) {
            envFile.recordTunnel(port, PID)
            val read = envFile.read().tunnel?.port ?: 0
            if (read < port) {
                System.err.println("wrote port $port, then read $read")
                exitProcess(LOST_UPDATE)
            }
        }
    }

    /** The PID every write records; the test only follows the port. */
    const val PID = 77L
}
