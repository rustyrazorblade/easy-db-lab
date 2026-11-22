package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A kit that names a known contributor nothing has registered in Koin (a wiring mistake) fails the
 * way a failing contributor does: [Event.Kit.ContributorFailed], and no step runs. Separate from
 * [KitRunnerCommandContributorTest], whose Koin modules register the contributor.
 */
class KitRunnerCommandUnregisteredContributorTest : KitRunnerCommandTestBase() {
    @Test
    fun `a contributor that is not registered fails through ContributorFailed, before any step`() {
        val name = Constants.Kit.Contributors.CASSANDRA_ARTIFACT
        writeKitYaml("pods", "name: pods\ncontributors: [$name]\n")
        val out = File(workingDir, "start.out")
        writeScript("pods", "start", """echo ran > "${out.absolutePath}"""")

        val events =
            captureEvents {
                assertThatThrownBy { command("pods", "start").call() }
                    .isInstanceOf(CommandFailedException::class.java)
                    .hasMessageContaining(name)
            }

        assertThat(out).doesNotExist()
        val failed = events.filterIsInstance<Event.Kit.ContributorFailed>().single()
        assertThat(failed.contributor).isEqualTo(name)
        assertThat(failed.phase).isEqualTo(Constants.Kit.PHASE_START)
        assertThat(failed.reason).contains("No kit variable contributor named '$name' is registered")
    }
}
