package com.rustyrazorblade.easydblab

/**
 * Matches the userinfo of a URL, with or without a password half: `https://user:token@host` and
 * the single-field `https://token@host` GitHub documents for personal access tokens are both
 * credentials. The mandatory `://` leaves an scp-style git remote (`git@github.com:acme/repo.git`)
 * alone — there the `git@` is a username with no secret attached.
 */
private val URL_CREDENTIALS = Regex("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\\s]+(:[^/@\\s]*)?@")

/**
 * Strips credentials out of any URL in [text].
 *
 * Remote commands can legitimately carry a git URL with an embedded token (the usual way to reach a
 * private fork), and that command — along with whatever the remote side echoes back — ends up in log
 * lines, exception messages, and serialized events that reach Redis subscribers.
 */
fun redactUrlCredentials(text: String): String = URL_CREDENTIALS.replace(text, "$1$REDACTED_USERINFO")

/** The userinfo [redactUrlCredentials] writes in place of a credential. */
private const val REDACTED_USERINFO = "***@"

/**
 * `key=value` style secrets: the value after `authkey=`, `token=`, `secret=`, `password=` or `key=`,
 * as a whole word or as the end of an environment name (`AWS_SECRET_ACCESS_KEY=`, `K3S_TOKEN=`).
 */
private val ASSIGNED_SECRET = Regex("(?i)\\b((?:\\w*_)?(?:authkey|token|secret|password|key))=\\S+")

/** An HTTP `Authorization:` header: the scheme and the credential after it. */
private val AUTHORIZATION_HEADER = Regex("(?i)(authorization:)\\s*\\S+\\s+\\S+")

/** An AWS access key id. */
private val AWS_ACCESS_KEY_ID = Regex("AKIA[0-9A-Z]{16}")

/** What [redact] writes in place of a secret value. */
private const val REDACTED = "***"

/**
 * Strips every credential a line of remote output, a command or an error can carry: URL userinfo
 * ([redactUrlCredentials]), `authkey=` / `token=` / `secret=` / `password=` / `key=` values, an
 * `Authorization:` header and an AWS access key id.  Node output, pod logs, debug logs and
 * exception text go through it; a command result a caller parses keeps [redactUrlCredentials] only.
 */
fun redact(text: String): String {
    val urls = redactUrlCredentials(text)
    val headers = AUTHORIZATION_HEADER.replace(urls) { "${it.groupValues[1]} $REDACTED" }
    val assigned = ASSIGNED_SECRET.replace(headers) { "${it.groupValues[1]}=$REDACTED" }
    return AWS_ACCESS_KEY_ID.replace(assigned, REDACTED)
}

/** Whether [url] is the output of [redactUrlCredentials] for a URL that carried a credential. */
fun hasRedactedCredentials(url: String): Boolean = "://$REDACTED_USERINFO" in url
