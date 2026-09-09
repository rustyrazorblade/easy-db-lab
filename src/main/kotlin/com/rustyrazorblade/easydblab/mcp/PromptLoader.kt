package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.services.FrontmatterMarkdownLoader
import com.rustyrazorblade.easydblab.services.MarkdownDocument
import io.github.classgraph.Resource
import java.io.InputStream

/**
 * Loads prompt resources from markdown files with YAML frontmatter.
 *
 * This is a thin adapter over [FrontmatterMarkdownLoader], the shared frontmatter parser. It maps
 * the generic [MarkdownDocument] the loader returns onto the MCP-specific [PromptResource] domain
 * type; all scanning and parsing lives in the shared loader.
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
    private val loader = FrontmatterMarkdownLoader()

    /**
     * Loads a single prompt from a ClassGraph Resource.
     *
     * @param resource The ClassGraph Resource representing the markdown file
     * @return PromptResource containing the parsed prompt data
     * @throws IllegalArgumentException if the file format is invalid or required fields are missing
     */
    fun loadPrompt(resource: Resource): PromptResource = loader.loadFromResource(resource).toPromptResource()

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
    ): PromptResource = loader.parseFromStream(inputStream, resourceName).toPromptResource()

    /**
     * Loads all prompts from markdown files in the specified package path.
     *
     * A single malformed or unreadable file is skipped and logged; the rest still load.
     *
     * @param packagePath Package path in dot notation (e.g., "com.rustyrazorblade.mcp")
     * @return List of PromptResource objects, one for each successfully loaded prompt
     */
    fun loadAllPrompts(packagePath: String): List<PromptResource> = loader.loadAll(packagePath).map { it.toPromptResource() }

    private fun MarkdownDocument.toPromptResource(): PromptResource =
        PromptResource(
            name = name,
            description = description,
            content = body,
        )
}
