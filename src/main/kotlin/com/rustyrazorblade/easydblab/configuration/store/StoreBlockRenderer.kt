package com.rustyrazorblade.easydblab.configuration.store

/**
 * Where a backend config's object-store block comes from: a fragment resource beside the config.
 *
 * A config at `<dir>/<file>.yaml` keeps one fragment per store case at `<dir>/store/<case>.yaml`.
 * The S3 fragments hold the lines that stood in each config before the store was neutral, comments
 * and indentation included, so the AWS config renders byte for byte as it did. The production
 * cases come from [of]; a test supplies its own source, because the sealed [StoreBackend] cannot be
 * extended outside its module.
 */
fun interface StoreBlockSource {
    /** The fragment for the config that sits beside [anchor]'s resources. */
    fun fragment(anchor: Class<*>): String

    companion object {
        private const val FRAGMENT_DIR = "store"

        /** The fragment named [name] in the `store/` directory beside [anchor]. */
        fun named(name: String): StoreBlockSource =
            StoreBlockSource { anchor ->
                val resource = "$FRAGMENT_DIR/$name.yaml"
                checkNotNull(anchor.getResourceAsStream(resource)) {
                    "Missing store fragment $resource beside ${anchor.simpleName}"
                }.bufferedReader().use { it.readText() }
            }

        /** The fragment source for [backend]'s case. A new case is a compile error here until it has fragments. */
        fun of(backend: StoreBackend): StoreBlockSource =
            when (backend) {
                is S3Backend -> named("s3")
            }
    }
}

/**
 * Places a backend's object-store block into its config text.
 *
 * Each config has one line holding only [PLACEHOLDER] where its block stands; the fragment from
 * [source] replaces that line, every non-blank fragment line indented to the placeholder's column.
 * This picks a resource file and places it, as `TemplateService` does: it builds no YAML. Builders
 * splice before any `__KEY__` substitution or `${VAR}` expansion, so the fragment's own
 * placeholders are filled exactly as the config's.
 *
 * @property source Where the fragment comes from.
 */
class StoreBlockRenderer(
    private val source: StoreBlockSource,
) {
    companion object {
        /** The line a config holds where its object-store block goes. */
        const val PLACEHOLDER = "__OBJECT_STORE_BLOCK__"

        private val PLACEHOLDER_LINE = Regex("""^([ ]*)${Regex.escape(PLACEHOLDER)}[ ]*$""", RegexOption.MULTILINE)

        /** The renderer for [backend]. */
        fun of(backend: StoreBackend): StoreBlockRenderer = StoreBlockRenderer(StoreBlockSource.of(backend))

        /**
         * The renderer for the store [backend] returns, asked each time a config is rendered: a
         * builder is created before the cluster has state, so it cannot resolve its store then.
         */
        fun of(backend: () -> StoreBackend): StoreBlockRenderer =
            StoreBlockRenderer { anchor -> StoreBlockSource.of(backend()).fragment(anchor) }
    }

    /** The classpath resource [name] beside [anchor], with its object-store block placed. */
    fun renderResource(
        anchor: Class<*>,
        name: String,
    ): String {
        val config =
            checkNotNull(anchor.getResourceAsStream(name)) { "Missing classpath resource $name next to ${anchor.simpleName}" }
                .bufferedReader()
                .use { it.readText() }
        return render(config, anchor)
    }

    /**
     * [config] with its placeholder line replaced by the fragment beside [anchor].
     *
     * @throws IllegalArgumentException unless [config] holds exactly one placeholder line.
     */
    fun render(
        config: String,
        anchor: Class<*>,
    ): String {
        val matches = PLACEHOLDER_LINE.findAll(config).toList()
        require(matches.size == 1) {
            "Expected exactly one $PLACEHOLDER line in ${anchor.simpleName}'s config, found ${matches.size}"
        }
        val match = matches.single()
        val indent = match.groupValues[1]
        val block =
            source
                .fragment(anchor)
                .removeSuffix("\n")
                .lines()
                .joinToString("\n") { if (it.isBlank()) "" else indent + it }
        return config.replaceRange(match.range, block)
    }
}
