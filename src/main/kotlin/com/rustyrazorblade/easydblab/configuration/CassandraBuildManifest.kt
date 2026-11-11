package com.rustyrazorblade.easydblab.configuration

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The record of one locally-produced Cassandra build, stored beside its tarball in S3.
 *
 * A build is identified by its [name] alone — that name is the S3 directory, the version a node
 * installs it under, and the handle `cassandra install` takes. Everything else here exists so a
 * build found in S3 weeks later can still be traced back to the tree it came from, which the name
 * on its own cannot do: a short sha does not say which repository it came from, and says nothing
 * at all when the tree was dirty.
 */
@Serializable
data class CassandraBuildManifest(
    /** The build's identity: `<version>-[JIRA-]<date>-<sha>-jdk<java>`. */
    val name: String,
    /** `base.version` as declared by the branch's build.xml, e.g. `5.1`. */
    val baseVersion: String,
    /** JDK major version the build ran under, e.g. `17`. */
    val javaVersion: String,
    val gitSha: String,
    val gitShortSha: String,
    val gitBranch: String,
    val gitRemote: String? = null,
    /** Whether the working tree carried uncommitted changes, making [gitSha] an incomplete record. */
    val dirty: Boolean = false,
    val jira: String? = null,
    /** Free-form label from `--name`, for telling otherwise-alike builds apart at a glance. */
    val label: String? = null,
    val antFlags: String? = null,
    /** ISO-8601 instant the build started. */
    val builtAt: String,
    /** The profile email of whoever produced it. */
    val builtBy: String,
    val tarball: String,
    val tarballBytes: Long,
    val tarballSha256: String,
) {
    fun encode(): String = JSON.encodeToString(this)

    companion object {
        /** Filename of the manifest within a build's S3 directory. */
        const val FILE_NAME = "manifest.json"

        /** Longest `--name` label accepted; the generated name is already long. */
        const val MAX_LABEL_LENGTH = 40

        private val JSON =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        fun parse(text: String): CassandraBuildManifest = JSON.decodeFromString(text)

        /**
         * The build's name, and so its S3 directory and the version nodes install it as.
         *
         * The JIRA and label segments are dropped entirely when absent, rather than filled with a
         * placeholder, so an unticketed unlabelled build reads as `5.1-20260905-a1b2c3d-jdk17`.
         *
         * The label sits after the ticket and before the date: it is part of what the build *is*,
         * not of which commit it came from, and keeping the version first means a flat bucket
         * listing still sorts by release.
         */
        fun buildName(
            baseVersion: String,
            jira: String?,
            date: LocalDate,
            shortSha: String,
            javaVersion: String,
            label: String? = null,
        ): String =
            listOfNotNull(
                stripSnapshot(baseVersion),
                jira?.uppercase()?.ifBlank { null },
                label?.trim()?.ifBlank { null },
                date.format(DateTimeFormatter.BASIC_ISO_DATE),
                shortSha,
                "jdk$javaVersion",
            ).joinToString("-")

        /**
         * `base.version` carries no -SNAPSHOT today, but a tree that appends one must not produce
         * a build named `5.1-SNAPSHOT-...`.
         */
        internal fun stripSnapshot(version: String): String = version.removeSuffix("-SNAPSHOT")

        /** The tarball name a build is stored under, so a downloaded file identifies itself. */
        fun tarballName(name: String): String = "apache-cassandra-$name-bin.tar.gz"

        /**
         * Checks a `--name` label is usable as part of a name that becomes an S3 key segment and a
         * directory on every node.
         *
         * Rejects rather than silently rewriting: a label quietly stripped of its spaces produces a
         * build whose name is not the one the operator asked for, and the name is the build's
         * identity everywhere afterwards. Says which characters were the problem.
         */
        fun validateLabel(label: String): String {
            val trimmed = label.trim()
            require(trimmed.isNotEmpty()) { "--name cannot be blank." }
            require(trimmed.length <= MAX_LABEL_LENGTH) {
                "--name is ${trimmed.length} characters; keep it to $MAX_LABEL_LENGTH or fewer."
            }
            val illegal = trimmed.filterNot { it.isLetterOrDigit() || it in "._-" }.toSortedSet()
            require(illegal.isEmpty()) {
                "--name may only contain letters, digits, '.', '_' and '-'. " +
                    "Remove: ${illegal.joinToString(" ") { "'$it'" }}"
            }
            return trimmed
        }
    }
}
