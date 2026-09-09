package com.rustyrazorblade.easydblab.services

/**
 * A markdown document parsed from a file with a YAML frontmatter header.
 *
 * This is the feature-neutral result of [FrontmatterMarkdownLoader]. It carries only the two
 * frontmatter fields the loader requires plus the body, so that unrelated features (MCP prompts,
 * help topics) can map it onto their own domain types without sharing a domain vocabulary.
 *
 * @property name The value of the frontmatter `name` field (the document's key).
 * @property description The value of the frontmatter `description` field (a one-line summary).
 * @property body The markdown content that follows the frontmatter header, trimmed.
 */
data class MarkdownDocument(
    val name: String,
    val description: String,
    val body: String,
)
