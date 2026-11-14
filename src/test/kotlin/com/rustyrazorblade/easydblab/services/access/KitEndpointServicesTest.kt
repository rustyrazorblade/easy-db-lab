package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.KitRuntime
import com.rustyrazorblade.easydblab.services.sql.KitService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Which Service a kit endpoint is published on, read from the kit's own files. */
class KitEndpointServicesTest {
    @TempDir
    lateinit var kitDir: File

    private fun endpoint(
        port: Int,
        service: String = "",
        servicePort: Int? = null,
    ) = KitEndpoint(
        name = "e$port",
        nodeType = "db",
        port = port,
        type = KitEndpoint.EndpointType.NATIVE,
        service = service,
        servicePort = servicePort,
    )

    private fun services(runtime: KitRuntime? = null) = KitEndpointServices.read(kitDir, KitConfig(name = "web", runtime = runtime))

    private fun manifest(
        name: String,
        vararg ports: Pair<Int, Int>,
    ) = File(kitDir, "$name.yaml").writeText(
        """
        |apiVersion: v1
        |kind: Service
        |metadata:
        |  name: $name
        |  namespace: web-ns
        |spec:
        |  type: NodePort
        |  ports:
        """.trimMargin() + ports.joinToString("") { (port, nodePort) -> "\n    - port: $port\n      nodePort: $nodePort" } + "\n",
    )

    @Test
    fun `an endpoint is published on the manifest Service that declares its NodePort, whatever its name`() {
        manifest("web-ui", 8081 to 30081)
        manifest("web-admin", 9000 to 30900)

        assertThat(services().of(endpoint(30081))).isEqualTo(KitService("web-ns", "web-ui", 8081))
        assertThat(services().of(endpoint(30900))).isEqualTo(KitService("web-ns", "web-admin", 9000))
    }

    @Test
    fun `an endpoint's declared Service wins over the manifests, in the runtime's namespace`() {
        manifest("web-nodeport", 9092 to 32100)
        val runtime = KitRuntime(type = KitRuntime.RuntimeType.PODS, namespace = "streams")

        assertThat(services(runtime).of(endpoint(32100, service = "web-external-bootstrap", servicePort = 9094)))
            .isEqualTo(KitService("streams", "web-external-bootstrap", 9094))
    }

    @Test
    fun `a Helm kit's endpoint no manifest declares is on the release's Service, its port unknown`() {
        val runtime = KitRuntime(type = KitRuntime.RuntimeType.HELM, release = "analytics", namespace = "olap")

        assertThat(services(runtime).of(endpoint(8080))).isEqualTo(KitService("olap", "analytics", null))
    }

    @Test
    fun `an endpoint of a non-Helm kit that no file declares has no Service`() {
        manifest("web-nodeport", 8080 to 31080)

        assertThat(services(KitRuntime(type = KitRuntime.RuntimeType.PODS)).of(endpoint(31090))).isNull()
    }
}
