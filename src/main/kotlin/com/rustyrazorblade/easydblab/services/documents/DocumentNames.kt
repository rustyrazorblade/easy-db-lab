package com.rustyrazorblade.easydblab.services.documents

import java.io.File

/**
 * One file `report upload` refuses, and why.
 *
 * @property name The file's name, as the operator gave it.
 * @property reason Why it is refused.
 */
data class RejectedDocument(
    val name: String,
    val reason: String,
)

/**
 * The rule every test document must follow before `report upload` sends it anywhere.
 *
 * A document is a markdown file whose name holds only `[A-Za-z0-9._-]`. The name travels through a
 * URL, the documents web server and a signing proxy, and this character set needs no encoding in
 * any of them. `index.md` is refused because its HTML copy would replace the test's index.
 */
object DocumentNames {
    /** The suffix of a document. */
    const val MARKDOWN_SUFFIX = ".md"

    /** The file name of a test's index. */
    const val INDEX_HTML = "index.html"

    private const val RESERVED = "index$MARKDOWN_SUFFIX"
    private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

    /** Every file of [files] that breaks the rule, in the order given. */
    fun rejected(files: List<File>): List<RejectedDocument> =
        files.mapNotNull { file -> reasonFor(file)?.let { RejectedDocument(file.name, it) } }

    /** The name of the HTML copy of the document named [name]. */
    fun htmlName(name: String): String = stem(name) + ".html"

    /** [name] without its `.md` suffix; the heading of the document in the index. */
    fun stem(name: String): String = name.removeSuffix(MARKDOWN_SUFFIX)

    private fun reasonFor(file: File): String? {
        val name = file.name
        return when {
            !file.exists() -> "does not exist"
            file.isDirectory -> "is a directory"
            !SAFE_NAME.matches(name) -> "names may hold only letters, digits, '.', '_' and '-'"
            !name.endsWith(MARKDOWN_SUFFIX) || name.length == MARKDOWN_SUFFIX.length -> "is not a markdown (.md) file"
            name == RESERVED -> "$RESERVED is reserved for the test's index"
            else -> null
        }
    }
}
