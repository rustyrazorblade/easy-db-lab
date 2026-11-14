package com.rustyrazorblade.easydblab.providers.aws

/**
 * Decides whether an IAM resource pattern covers an S3 object, with IAM's wildcard rules: `*`
 * matches any run of characters, `/` included, and `?` matches any one character.
 *
 * The policy tests use it to check what a policy document reaches, not which strings it holds: a
 * wildcard in the bucket part of an ARN also reaches keys deeper in the bucket, which a test
 * comparing resource strings would miss.
 */
object IamResourceMatcher {
    /** Whether [pattern] covers the object [key] in [bucket]. */
    fun covers(
        pattern: String,
        bucket: String,
        key: String,
    ): Boolean = toRegex(pattern).matches("arn:aws:s3:::$bucket/$key")

    /** Whether any of [patterns] covers the object [key] in [bucket]. */
    fun anyCovers(
        patterns: List<String>,
        bucket: String,
        key: String,
    ): Boolean = patterns.any { covers(it, bucket, key) }

    private fun toRegex(pattern: String): Regex =
        Regex(
            pattern
                .map { char ->
                    when (char) {
                        '*' -> ".*"
                        '?' -> "."
                        else -> Regex.escape(char.toString())
                    }
                }.joinToString(""),
        )
}
