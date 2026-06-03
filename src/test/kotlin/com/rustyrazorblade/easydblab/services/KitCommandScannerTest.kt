package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [DefaultKitCommandScanner]. Verifies that @KitCommand-annotated classes
 * on the test classpath are correctly discovered with kit name, subcommand name,
 * and description sourced from @Command.
 */
class KitCommandScannerTest {
    private val scanner = DefaultKitCommandScanner()

    @Test
    fun `finds PrestoSql annotated with KitCommand for presto`() {
        val commands = scanner.forKit("presto")
        assertThat(commands).isNotEmpty
        val sql = commands.find { it.name == "sql" }
        assertThat(sql).isNotNull
        assertThat(sql!!.kit).isEqualTo("presto")
        assertThat(sql.cls.name).endsWith("PrestoSql")
    }

    @Test
    fun `reads description from @Command annotation`() {
        val sql = scanner.forKit("presto").find { it.name == "sql" }
        assertThat(sql).isNotNull
        assertThat(sql!!.description).isEqualTo("Execute SQL against the Presto cluster")
    }

    @Test
    fun `findAll groups results by kit name`() {
        val all = scanner.findAll()
        assertThat(all).containsKey("presto")
        assertThat(all["presto"]).anyMatch { it.name == "sql" }
    }

    @Test
    fun `result is cached across calls`() {
        val first = scanner.findAll()
        val second = scanner.findAll()
        assertThat(first).isSameAs(second)
    }
}
