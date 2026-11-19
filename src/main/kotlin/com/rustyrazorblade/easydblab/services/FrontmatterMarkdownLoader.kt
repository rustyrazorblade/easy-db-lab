package com.rustyrazorblade.easydblab.services

import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassGraphException
import io.github.classgraph.Resource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.IOException
import java.io.InputStream

/**
 * Loads markdown documents that carry a YAML frontmatter header with `name` and `description`
 * fields.
 *
 * This is the single, feature-neutral frontmatter parser for the codebase. It uses ClassGraph to
 * scan classpath resources, which resolves correctly both in development (filesystem) and in a
 * packaged distribution (JAR) — so features built on it work identically from a Homebrew install
 * with no source checkout.
 *
 * The help-topic service consumes this loader rather than owning a copy
 * of the parsing logic.
 *
 * Expected file format:
 * ```
 * ---
 * name: document-name
 * description: Document description
 * ---
 * Body content in markdown format
 * ```
 */
class FrontmatterMarkdownLoader {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val FRONTMATTER_DELIMITER = "---"
    }

    private val yaml = Yaml()

    /**
     * Loads a single document from a ClassGraph [Resource].
     *
     * @throws IllegalArgumentException if the format is invalid or a required field is missing.
     */
    fun loadFromResource(resource: Resource): MarkdownDocument {
        val content = resource.contentAsString
        val resourceName = resource.path.substringAfterLast('/')
        return parse(content, resourceName)
    }

    /**
     * Loads a single document from an [InputStream] (useful for testing).
     *
     * @throws IllegalArgumentException if the format is invalid or a required field is missing.
     */
    fun parseFromStream(
        inputStream: InputStream,
        resourceName: String,
    ): MarkdownDocument {
        val content = inputStream.bufferedReader().use { it.readText() }
        return parse(content, resourceName)
    }

    /**
     * Parses document content from a markdown string.
     *
     * @throws IllegalArgumentException if the format is invalid or a required field is missing.
     */
    fun parse(
        content: String,
        resourceName: String,
    ): MarkdownDocument {
        val parts = validateAndSplitContent(resourceName, content)
        val frontmatterText = parts[0].trim()
        val body = parts[1].trim()

        val frontmatter = parseFrontmatter(resourceName, frontmatterText)
        val name = extractRequiredField(resourceName, frontmatter, "name")
        val description = extractRequiredField(resourceName, frontmatter, "description")

        return MarkdownDocument(
            name = name,
            description = description,
            body = body,
        )
    }

    /**
     * Loads all documents from markdown files in the given classpath package.
     *
     * A single missing, malformed, or unreadable file is skipped and logged; the remaining
     * well-formed files still load. The per-resource handler covers the two ways a file can be bad
     * (an [IllegalArgumentException] from the parse, an [IOException] from reading it), so one bad
     * file can never zero out the whole result set. Only a failure of the scan itself yields an
     * empty list.
     *
     * @param packagePath Package path in dot notation (e.g., "com.rustyrazorblade.easydblab.help").
     * @return One [MarkdownDocument] per successfully loaded file.
     */
    fun loadAll(packagePath: String): List<MarkdownDocument> {
        log.info { "Scanning for markdown resources in package: $packagePath" }

        return try {
            ClassGraph()
                .acceptPackages(packagePath)
                .scan()
                .use { scanResult ->
                    val resources = scanResult.getResourcesWithExtension("md")
                    log.info { "Found ${resources.size} markdown resources in $packagePath" }

                    resources.mapNotNull { resource -> loadOrSkip(resource) }
                }
        } catch (e: ClassGraphException) {
            log.error(e) { "Error scanning for markdown resources in package $packagePath" }
            emptyList()
        }
    }

    private fun loadOrSkip(resource: Resource): MarkdownDocument? =
        try {
            loadFromResource(resource)
        } catch (e: IllegalArgumentException) {
            log.warn { "Skipping markdown resource ${resource.path}: ${e.message}" }
            null
        } catch (e: IOException) {
            log.warn { "Skipping markdown resource ${resource.path}: ${e.message}" }
            null
        }

    /**
     * Validates content format and splits it into frontmatter and body.
     */
    private fun validateAndSplitContent(
        resourceName: String,
        content: String,
    ): List<String> {
        require(content.startsWith(FRONTMATTER_DELIMITER)) {
            "Resource $resourceName does not contain valid YAML frontmatter. " +
                "Expected to start with '$FRONTMATTER_DELIMITER'"
        }

        val parts = content.substring(FRONTMATTER_DELIMITER.length).split(FRONTMATTER_DELIMITER, limit = 2)

        require(parts.size >= 2) {
            "Resource $resourceName does not contain properly closed YAML frontmatter. " +
                "Expected closing '$FRONTMATTER_DELIMITER'"
        }

        return parts
    }

    /**
     * Parses YAML frontmatter text into a map.
     *
     * @throws IllegalArgumentException if the text is not YAML or is not a YAML map.
     */
    private fun parseFrontmatter(
        resourceName: String,
        frontmatterText: String,
    ): Map<String, Any> {
        val loaded =
            try {
                yaml.load<Any?>(frontmatterText)
            } catch (e: YAMLException) {
                throw IllegalArgumentException("Failed to parse YAML frontmatter in $resourceName: ${e.message}", e)
            }
        val map = loaded as? Map<*, *> ?: throw IllegalArgumentException("Frontmatter is not a valid YAML map")
        // A key with no value is dropped, so the required-field check reports it as missing.
        return map.mapNotNull { (key, value) -> value?.let { key.toString() to it } }.toMap()
    }

    /**
     * Extracts a required field from frontmatter.
     */
    private fun extractRequiredField(
        resourceName: String,
        frontmatter: Map<String, Any>,
        fieldName: String,
    ): String =
        frontmatter[fieldName] as? String
            ?: throw IllegalArgumentException(
                "Missing required '$fieldName' field in frontmatter of $resourceName",
            )
}
