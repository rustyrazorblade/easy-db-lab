package com.rustyrazorblade.jvmpauseagent

import java.lang.instrument.Instrumentation

object JvmPauseAgent {
    @JvmStatic
    fun premain(
        agentArgs: String?,
        instrumentation: Instrumentation?,
    ) {
        val detector = JvmPauseDetector()
        detector.isDaemon = true
        detector.name = "jvm-pause-detector"
        detector.start()
    }
}
