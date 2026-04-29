package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayOutputStream

private val log = KotlinLogging.logger {}

class DefaultK8sPodOperations(
    private val clientProvider: K8sClientProvider,
) : K8sPodOperations {
    override fun execInPod(
        controlHost: ClusterHost,
        namespace: String,
        podName: String,
        command: List<String>,
    ): Result<String> =
        runCatching {
            log.info { "Executing in pod $podName: ${command.joinToString(" ")}" }
            clientProvider.createClient(controlHost).use { client ->
                val output = ByteArrayOutputStream()
                val error = ByteArrayOutputStream()
                val execWatch =
                    client
                        .pods()
                        .inNamespace(namespace)
                        .withName(podName)
                        .writingOutput(output)
                        .writingError(error)
                        .exec(*command.toTypedArray())
                try {
                    val exitCode = execWatch.exitCode().get()
                    val stdout = output.toString(Charsets.UTF_8)
                    val stderr = error.toString(Charsets.UTF_8)
                    if (exitCode != 0) {
                        error("Command in pod $podName failed (exit $exitCode): $stderr")
                    }
                    stdout
                } finally {
                    execWatch.close()
                }
            }
        }
}
