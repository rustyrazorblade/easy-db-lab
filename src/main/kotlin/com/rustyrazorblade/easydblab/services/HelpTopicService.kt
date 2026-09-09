package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * A single help topic discovered from a packaged markdown resource.
 *
 * This is a distinct type from the MCP `PromptResource` so that help does not inherit MCP-prompt
 * semantics, even though both are loaded by the shared [FrontmatterMarkdownLoader].
 *
 * @property name The topic key (the frontmatter `name` field), used for lookup and listing.
 * @property description The one-line summary shown in the no-argument listing.
 * @property body The topic's markdown body, printed verbatim when the topic is requested.
 */
data class HelpTopic(
    val name: String,
    val description: String,
    val body: String,
)

/**
 * Discovers task-oriented help topics from packaged markdown resources and resolves them by key.
 *
 * Topics are packaged classpath resources, so discovery works identically from a Homebrew install
 * with no source checkout. Adding a topic is adding a markdown file with a valid frontmatter
 * header; no code change is required.
 */
interface HelpTopicService {
    /** Returns every discovered topic. */
    fun findAll(): List<HelpTopic>

    /** Resolves a topic by key, case-insensitively. Returns null when no topic matches. */
    fun find(key: String): HelpTopic?
}

/**
 * Default [HelpTopicService] backed by [FrontmatterMarkdownLoader] over the help resource package.
 *
 * The scan is cached with `by lazy` so its cost is paid once, and only when help is first used —
 * mirroring [DefaultKitCommandScanner].
 */
class DefaultHelpTopicService(
    private val loader: FrontmatterMarkdownLoader = FrontmatterMarkdownLoader(),
    private val resourcePackage: String = Constants.Help.RESOURCE_PACKAGE,
) : HelpTopicService {
    private val topics: List<HelpTopic> by lazy { scan() }

    override fun findAll(): List<HelpTopic> = topics

    override fun find(key: String): HelpTopic? = topics.firstOrNull { it.name.equals(key, ignoreCase = true) }

    private fun scan(): List<HelpTopic> {
        log.debug { "Scanning classpath for help topics in $resourcePackage" }
        return loader
            .loadAll(resourcePackage)
            .map { HelpTopic(name = it.name, description = it.description, body = it.body) }
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
