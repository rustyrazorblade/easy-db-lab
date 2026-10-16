package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [DefaultHelpTopicService] against a fixture package that mixes well-formed topics with
 * one malformed file.
 *
 * Verifies discovery, the no-argument listing set, case-insensitive resolution, and that a
 * malformed file is skipped without dropping the well-formed topics.
 */
class HelpTopicServiceTest {
    private val fixturePackage = "com.rustyrazorblade.easydblab.helptopictest"

    private fun service(): HelpTopicService = DefaultHelpTopicService(resourcePackage = fixturePackage)

    @Test
    fun `findAll discovers the well-formed topics and skips the malformed file`() {
        val names = service().findAll().map { it.name }

        assertThat(names).containsExactlyInAnyOrder("alpha", "beta")
    }

    @Test
    fun `find resolves a topic by its exact key`() {
        val topic = service().find("alpha")

        assertThat(topic).isNotNull
        assertThat(topic!!.body).contains("How to do the alpha operation")
    }

    @Test
    fun `find resolves a topic case-insensitively`() {
        val lower = service().find("beta")
        val mixed = service().find("BeTa")

        assertThat(mixed).isEqualTo(lower)
    }

    @Test
    fun `find returns null for an unknown key`() {
        assertThat(service().find("nonsense")).isNull()
    }

    /** The topic states the rules `init` enforces, so it must name them as the code does. */
    @Test
    fun `the provisioning topic describes init --experiment with the rules init enforces`() {
        val body = DefaultHelpTopicService().find("provisioning")!!.body

        assertThat(body)
            .contains("--experiment")
            .contains(Constants.Observability.EXPERIMENT_PATTERN)
            .contains(Constants.Observability.CLUSTER_NAME_PATTERN)
    }

    @Test
    fun `the observability topic describes the local stack commands`() {
        val body = DefaultHelpTopicService().find("observability")!!.body

        assertThat(body)
            .contains("observability local create <dir>")
            .contains("--tenant")
            .contains("observability local dashboards <dir>")
            .contains("--from")
    }

    @Test
    fun `the packaged seed topics load with valid frontmatter and a body`() {
        // Guards the real seed resources against a malformed frontmatter header slipping in.
        val service = DefaultHelpTopicService()

        val names = service.findAll().map { it.name }
        assertThat(names).containsExactlyInAnyOrder(
            "provisioning",
            "kits",
            "stress-testing",
            "profiles",
            "connecting",
            "querying",
            "observability",
            "spark",
            "cassandra",
            "cassandra-builds",
        )

        service.findAll().forEach { topic ->
            assertThat(topic.description).isNotBlank()
            assertThat(topic.body).isNotBlank()
        }
    }
}
