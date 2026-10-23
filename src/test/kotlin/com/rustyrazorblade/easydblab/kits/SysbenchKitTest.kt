package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.interpolateStepVars
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The sysbench kit's contracts that span its files: the credentials Secret its phases write
 * (`bin/lib/target-db.sh`, through the shared library's labels) must be removed by `kit uninstall`
 * too, so a target password does not outlive an instance uninstalled without a `stop`.
 */
class SysbenchKitTest : BaseKoinTest() {
    private val templateService by lazy { TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()) }
    private val kit by lazy { BuiltinKitFixture("sysbench", templateService) }

    /** The role target-db.sh gives the credentials Secret, which edl_apply_secret labels it with. */
    private val credentialsRole: String by lazy {
        requireNotNull(
            Regex("""^SYSBENCH_CREDENTIALS_ROLE=(\S+)$""", RegexOption.MULTILINE)
                .find(kit.resource("bin/lib/target-db.sh"))
                ?.groupValues
                ?.get(1),
        ) { "target-db.sh sets no SYSBENCH_CREDENTIALS_ROLE" }
    }

    @Test
    fun `uninstall deletes this instance's credentials Secret by the labels the phases write`() {
        val deletes =
            kit.config.uninstall
                .filterIsInstance<InstallStep.Delete>()
                .filter { it.bySelector }
        val secretDelete = deletes.single { it.kinds == listOf("Secret") }

        assertThat(interpolateStepVars(secretDelete.selector, mapOf("KIT_NAME" to "sysbench-tidb")))
            .isEqualTo("easydblab/kit=sysbench-tidb,easydblab/role=$credentialsRole")
        assertThat(secretDelete.namespace ?: "default").isEqualTo("default")
    }
}
