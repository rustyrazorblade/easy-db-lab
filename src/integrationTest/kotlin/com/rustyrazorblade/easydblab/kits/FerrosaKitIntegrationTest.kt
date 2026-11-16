package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.dsl.FieldValidateable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Sends the objects the FerrosaDB kit's `start` would apply — each db host's PVC, StatefulSet and
 * ClusterIP Service for a 3-node ring, and the seven NodePort Services — to a real K3s API server
 * as a server-side dry run with strict field validation. The API server must accept every object:
 * a value its validation refuses (a port out of range, a label or name it does not allow, a missing
 * required field) fails here, where the unit tests' fabric8 parse would accept it. A dry run
 * persists nothing, so the shared cluster's `default` namespace, which the manifests name, is left
 * as it was.
 */
class FerrosaKitIntegrationTest : BaseKoinTest() {
    private val kit by lazy {
        BuiltinKitFixture("ferrosa", TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
    }

    private fun startObjects(): List<HasMetadata> {
        val run =
            FerrosaApplyRun(kit, File(tempDir, "stub"), dbNodes = 3, env = mapOf("REGION" to "us-west-2", "BUCKET_NAME" to "data"))
        assertThat(run.exit).describedAs(run.stub.output()).isZero()
        return run.objects + kit.render("nodeport-service.yaml.template")
    }

    @Test
    fun `the API server accepts every object start applies`() {
        val objects = startObjects()

        assertThat(objects.map { "${it.kind}/${it.metadata.name}" })
            .contains("StatefulSet/ferrosa-2", "PersistentVolumeClaim/ferrosa-data-2", "Service/ferrosa-2", "Service/ferrosa-cql-nodeport")
        SharedK3s.client().use { client ->
            for (obj in objects) {
                assertThatCode {
                    client
                        .resource(obj)
                        .dryRun()
                        .fieldValidation(FieldValidateable.Validation.STRICT)
                        .create()
                }.describedAs("${obj.kind}/${obj.metadata.name}").doesNotThrowAnyException()
            }
        }
    }
}
