package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.localstack.ComposeFile
import com.rustyrazorblade.easydblab.localstack.ComposeMount
import com.rustyrazorblade.easydblab.localstack.ComposeService
import java.io.File

/**
 * A tiny query export for [KilledExportIntegrationTest], run in its own JVM: it starts one temporary
 * stack the way the export does ([DockerExportStack.start] over the real `docker compose`), prints
 * [UP], and waits to be killed. Its one service is a busybox web server that answers `/ready`.
 */
object KilledExportHarness {
    /** What the harness prints once its stack is up. */
    const val UP = "STACK UP"

    /** The web server's port in its container. */
    const val PORT = 8080

    /** The busybox the web server runs in. */
    const val IMAGE = Constants.Compactor.CONFIG_IMAGE

    /** The stack's files: the compose file and the page that answers `/ready`. */
    fun files(): Map<String, ByteArray> =
        mapOf(
            Constants.LocalStack.COMPOSE_FILE to
                ComposeFile(
                    mapOf(
                        "web" to
                            ComposeService(
                                image = IMAGE,
                                command = listOf("httpd", "-f", "-v", "-p", PORT.toString(), "-h", "/www"),
                                volumes = listOf(ComposeMount.bind("./www", "/www", readOnly = true)),
                                ports = listOf("${Constants.LocalStack.LOOPBACK}::$PORT"),
                            ),
                    ),
                ).toYaml().toByteArray(),
            "www/ready" to "ready\n".toByteArray(),
        )

    /** `<directory> <project>`: starts the stack in the directory and waits. */
    @JvmStatic
    fun main(args: Array<String>) {
        val (directory, project) = args
        DockerExportStack.start(
            File(directory),
            StackDefinition(project = project, files = files(), published = mapOf("web" to listOf(PORT))),
            StackRuntime(DockerComposeCli, EventBus()),
        )
        println(UP)
        System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }
}
