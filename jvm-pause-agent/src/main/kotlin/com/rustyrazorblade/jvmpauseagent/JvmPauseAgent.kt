package com.rustyrazorblade.jvmpauseagent

import java.lang.instrument.Instrumentation

class JvmPauseAgent {
    companion object {
        @JvmStatic
        fun premain(args: String?, instrumentation: Instrumentation?) {
            val endpoint = System.getProperty("jvm.pause.agent.endpoint", "http://localhost:4317")
            val clusterName = System.getProperty("jvm.pause.agent.cluster.name", "unknown")
            val hostname = System.getenv("HOSTNAME") ?: try {
                java.net.InetAddress.getLocalHost().hostName
            } catch (e: Exception) {
                "unknown"
            }

            val detector = JvmPauseDetector(endpoint, clusterName, hostname)
            detector.isDaemon = true
            detector.name = "jvm-pause-detector"
            detector.start()
        }
    }
}
