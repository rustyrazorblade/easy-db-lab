package com.rustyrazorblade.easydblab.mcp

import io.github.classgraph.ClassGraph
import io.github.classgraph.Resource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.yaml.snakeyaml.Yaml
import java.io.InputStream

/**
 * Loads prompt resources from markdown files with YAML frontmatter.
 *
 * Uses ClassGraph to scan classpath resources, which works correctly in both
 * development (filesystem) and production (JAR) environments.
 *
 * Expected file format:
 * ```
 * ---
 * name: prompt-name
 * description: Prompt description
 * ---
 * Prompt content in markdown format
 * ```
 */
class PromptLoader {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val FRONTMATTER_DELIMITER = "---"
    }

    private val yaml = Yaml()

    /**
     * Loads a single prompt from a ClassGraph Resource.
     *
     * @param resource The ClassGraph Resource representing the markdown file
     * @return PromptResource containing the parsed prompt data
     * @throws IllegalArgumentException if the file format is invalid or required fields are missing
     */
    fun loadPrompt(resource: Resource): PromptResource {
        val content = resource.contentAsString
        val resourceName = resource.path.substringAfterLast('/')
        return parsePromptContent(content, resourceName)
    }

    /**
     * Loads a single prompt from an InputStream (useful for testing).
     *
     * @param inputStream The input stream containing the markdown content
     * @param resourceName The name of the resource (for error messages)
     * @return PromptResource containing the parsed prompt data
     * @throws IllegalArgumentException if the file format is invalid or required fields are missing
     */
    fun loadPromptFromStream(
        inputStream: InputStream,
        resourceName: String,
    ): PromptResource {
        val content = inputStream.bufferedReader().use { it.readText() }
        return parsePromptContent(content, resourceName)
    }

    /**
     * Parses prompt content from markdown string.
     */
    private fun parsePromptContent(
        content: String,
        resourceName: String,
    ): PromptResource {
        val parts = validateAndSplitContent(resourceName, content)
        val frontmatterText = parts[0].trim()
        val promptContent = parts[1].trim()

        val frontmatter = parseFrontmatter(resourceName, frontmatterText)
        val name = extractRequiredField(resourceName, frontmatter, "name")
        val description = extractRequiredField(resourceName, frontmatter, "description")

        return PromptResource(
            name = name,
            description = description,
            content = promptContent,
        )
    }

    /**
     * Validates content format and splits into frontmatter and body.
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
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private fun parseFrontmatter(
        resourceName: String,
        frontmatterText: String,
    ): Map<String, Any> {
        try {
            @Suppress("UNCHECKED_CAST")
            return yaml.load(frontmatterText) as? Map<String, Any>
                ?: throw IllegalArgumentException("Frontmatter is not a valid YAML map")
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Failed to parse YAML frontmatter in $resourceName: ${e.message}",
                e,
            )
        }
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

    /**
     * Loads all prompts from markdown files in the specified package path using ClassGraph.
     *
     * This method works correctly in both development (filesystem) and production (JAR) environments
     * by scanning the classpath for resources rather than filesystem directories.
     *
     * @param packagePath Package path in dot notation (e.g., "com.rustyrazorblade.mcp")
     * @return List of PromptResource objects, one for each successfully loaded prompt
     */
    @Suppress("TooGenericExceptionCaught")
    fun loadAllPrompts(packagePath: String): List<PromptResource> {
        log.info { "Scanning for prompt resources in package: $packagePath" }

        return try {
            ClassGraph()
                .acceptPackages(packagePath)
                .scan()
                .use { scanResult ->
                    val resources = scanResult.getResourcesWithExtension("md")
                    log.info { "Found ${resources.size} markdown resources in $packagePath" }

                    resources.mapNotNull { resource ->
                        try {
                            loadPrompt(resource)
                        } catch (e: IllegalArgumentException) {
                            log.warn { "Failed to load prompt from ${resource.path}: ${e.message}" }
                            null
                        }
                    }
                }
        } catch (e: Exception) {
            log.error(e) { "Error scanning for prompts in package $packagePath" }
            emptyList()
        }
    }
}
