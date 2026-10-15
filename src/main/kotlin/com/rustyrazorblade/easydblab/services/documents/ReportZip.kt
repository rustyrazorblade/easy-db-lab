package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.Constants
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readBytes

/**
 * One path of a report directory that `report upload` refuses, and why.
 *
 * @property path The path relative to the directory, or the directory itself as `<dir>/`.
 * @property reason Why it is refused; several rules a path breaks are joined.
 */
data class ReportFileProblem(
    val path: String,
    val reason: String,
)

/**
 * A report directory breaks the rules; nothing was built or uploaded.
 *
 * @property problems Every refused path and why.
 */
class ReportRejectedException(
    val problems: List<ReportFileProblem>,
) : IllegalArgumentException(
        "Nothing was uploaded. Rejected: " + problems.joinToString("; ") { "${it.path} ${it.reason}" },
    )

/**
 * The zip of one report directory, and the files in it.
 *
 * @property bytes The zip, exactly as it is stored.
 * @property files Each file's path relative to the directory, `/`-separated, in path order.
 */
class ReportArchive(
    val bytes: ByteArray,
    val files: List<String>,
)

/**
 * Checks a report directory and builds its zip.
 *
 * A report is one directory of any file types. Every file is served by the documents web server,
 * so each path segment must match [Constants.Observability.REPORT_PATH_SEGMENT_PATTERN] and hold no
 * `..`; no entry may be a symbolic link (it is never followed); the root may not hold the generated
 * file list, [Constants.Observability.REPORT_FILE_LIST]; and no `X.md` may sit beside an `X.html`,
 * because its HTML copy would replace that file. The whole directory is checked before anything is
 * built, so every offender is named in one failure.
 */
object ReportZip {
    private val SEGMENT = Regex(Constants.Observability.REPORT_PATH_SEGMENT_PATTERN)
    private const val HTML_SUFFIX = ".html"
    private const val PARENT = ".."

    /** Every path of [dir] that breaks a rule, one entry per path, in path order; empty when [dir] is a valid report. */
    fun problems(dir: File): List<ReportFileProblem> {
        val root = dir.toPath()
        val whole = "${dir.path}/"
        return when {
            !Files.exists(root, LinkOption.NOFOLLOW_LINKS) -> listOf(ReportFileProblem(whole, "does not exist"))
            !root.isDirectory(LinkOption.NOFOLLOW_LINKS) -> listOf(ReportFileProblem(whole, "is not a directory"))
            else -> problemsIn(root, whole)
        }
    }

    /**
     * The zip of [dir]: every file under its relative path, in path order, dated with its own
     * modification time, so building the same directory twice gives the same bytes.
     *
     * @throws ReportRejectedException naming every offender, before anything is built, if [dir] breaks a rule.
     */
    fun build(dir: File): ReportArchive {
        val problems = problems(dir)
        if (problems.isNotEmpty()) throw ReportRejectedException(problems)
        val root = dir.toPath()
        val files = filesUnder(root).map { relative(root, it) }.sorted()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { path ->
                val file = root.resolve(path)
                // The file's own time, never the clock, so the same directory always gives the same zip.
                zip.putNextEntry(ZipEntry(path).apply { time = Files.getLastModifiedTime(file).toMillis() })
                zip.write(file.readBytes())
                zip.closeEntry()
            }
        }
        return ReportArchive(out.toByteArray(), files)
    }

    private fun problemsIn(
        root: Path,
        whole: String,
    ): List<ReportFileProblem> {
        val entries = Files.walk(root).use { walk -> walk.filter { it != root }.toList() }
        val links = entries.filter { it.isSymbolicLink() }.map { relative(root, it) }
        val files = entries.filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }.map { relative(root, it) }
        val reasons = mutableMapOf<String, MutableList<String>>()

        fun refuse(
            path: String,
            reason: String,
        ) = reasons.getOrPut(path) { mutableListOf() }.add(reason)

        links.forEach { refuse(it, "is a symbolic link; a report holds only regular files") }
        files.forEach { path ->
            if (path.contains(PARENT)) refuse(path, "holds '$PARENT'")
            if (!path.split('/').all { SEGMENT.matches(it) }) {
                refuse(path, "path segments may hold only letters, digits, '.', '_' and '-'")
            }
        }
        if (Constants.Observability.REPORT_FILE_LIST in files) {
            refuse(Constants.Observability.REPORT_FILE_LIST, "is reserved for the file list report upload generates")
        }
        markdownBesideHtml(files.toSet()).forEach { (markdown, html) ->
            refuse(markdown, "has a sibling $html, which its HTML copy would replace")
            refuse(html, "would be replaced by the HTML copy of $markdown")
        }
        val problems = reasons.map { (path, why) -> ReportFileProblem(path, why.joinToString("; ")) }.sortedBy { it.path }
        return if (files.isEmpty()) problems + ReportFileProblem(whole, "holds no file") else problems
    }

    private fun markdownBesideHtml(files: Set<String>): List<Pair<String, String>> =
        files
            .filter { it.endsWith(Constants.Observability.REPORT_MARKDOWN_SUFFIX) }
            .map { it to it.removeSuffix(Constants.Observability.REPORT_MARKDOWN_SUFFIX) + HTML_SUFFIX }
            .filter { (_, html) -> html in files }

    private fun filesUnder(root: Path): List<Path> =
        Files.walk(root).use { walk -> walk.filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }.toList() }

    private fun relative(
        root: Path,
        path: Path,
    ): String = root.relativize(path).joinToString("/")
}
