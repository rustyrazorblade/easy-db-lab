package com.rustyrazorblade.easydblab.services

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
        )

        service.findAll().forEach { topic ->
            assertThat(topic.description).isNotBlank()
            assertThat(topic.body).isNotBlank()
        }
    }
}
