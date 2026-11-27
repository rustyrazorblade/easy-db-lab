package com.rustyrazorblade.easydblab.configuration.profile

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.EmptySerializersModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** A list or map entry: the class the finder must check inside a collection. */
@Serializable
private data class Entry(
    val name: String = "",
    val size: Int = 0,
)

/** A document whose fields hold classes inside a list, a map, and a map nested in a list. */
@Serializable
private data class Catalog(
    val title: String = "",
    val tags: List<String> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val byName: Map<String, Entry> = emptyMap(),
    val groups: List<Map<String, Entry>> = emptyList(),
)

/**
 * The unknown-key walk over lists and maps of classes, which no profile field has today: a key
 * inside one must be found by the walk, never left to the strict decode, whose error names the key
 * unredacted. A map key is file text, so it never appears in a path and is never reported.
 */
class UnknownKeyFinderTest {
    private val finder = UnknownKeyFinder(EmptySerializersModule(), typeKey = "type")

    private fun find(document: String): UnknownKey? =
        finder.find(Yaml.default.parseToYamlNode(document.trimIndent()), Catalog.serializer().descriptor)

    @ParameterizedTest(name = "{0}")
    @MethodSource("withUnknownKey", "withUnknownKeyUnderTag")
    internal fun `finds the first undeclared key in a list or map of classes`(
        case: String,
        document: String,
        expected: UnknownKey,
    ) {
        assertThat(find(document)).describedAs(case).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("withoutUnknownKey")
    fun `reports nothing when every field name is declared`(
        case: String,
        document: String,
    ) {
        assertThat(find(document)).describedAs(case).isNull()
    }

    companion object {
        private val ENTRY_KEYS = listOf("name", "size")
        private val CATALOG_KEYS = listOf("title", "tags", "entries", "byName", "groups")

        @JvmStatic
        fun withUnknownKey(): List<Arguments> =
            listOf(
                Arguments.of(
                    "unknown key in a list item",
                    """
                    entries:
                      - name: a
                      - name: b
                        sise: 3
                    """,
                    UnknownKey("entries[1]", "sise", ENTRY_KEYS, 4, 5),
                ),
                Arguments.of(
                    "unknown key in a map value, path without the map key",
                    """
                    byName:
                      secretLookingKey:
                        name: a
                        nmae: b
                    """,
                    UnknownKey("byName.<key>", "nmae", ENTRY_KEYS, 4, 5),
                ),
                Arguments.of(
                    "unknown key in a map inside a list",
                    """
                    groups:
                      - first:
                          size: 1
                      - second:
                          colour: red
                    """,
                    UnknownKey("groups[1].<key>", "colour", ENTRY_KEYS, 5, 7),
                ),
                Arguments.of(
                    "first unknown key in file order across shapes",
                    """
                    byName:
                      x:
                        bogus: 1
                    entries:
                      - other: 2
                    """,
                    UnknownKey("byName.<key>", "bogus", ENTRY_KEYS, 3, 5),
                ),
                Arguments.of(
                    "unknown key at the top level beside collections",
                    """
                    entries:
                      - name: a
                    titel: t
                    """,
                    UnknownKey("", "titel", CATALOG_KEYS, 3, 1),
                ),
            )

        /** kaml decodes through a YAML tag, so the walk must find a key under one too. */
        @JvmStatic
        fun withUnknownKeyUnderTag(): List<Arguments> =
            listOf(
                Arguments.of(
                    "unknown key in a tagged list item",
                    """
                    entries: !things
                      - !thing
                        name: a
                        sise: 3
                    """,
                    UnknownKey("entries[0]", "sise", ENTRY_KEYS, 4, 5),
                ),
            )

        @JvmStatic
        fun withoutUnknownKey(): List<Arguments> =
            listOf(
                Arguments.of(
                    "free-form map keys are not reported",
                    """
                    byName:
                      anything: {}
                      AKIAIOSFODNN7EXAMPLE:
                        name: a
                    """,
                ),
                Arguments.of(
                    "a fully valid document",
                    """
                    title: t
                    tags: [a, b]
                    entries:
                      - name: a
                        size: 1
                    byName:
                      k:
                        size: 2
                    groups:
                      - g:
                          name: c
                    """,
                ),
            )
    }
}
