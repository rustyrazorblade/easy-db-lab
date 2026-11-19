package com.rustyrazorblade.easydblab.ssh

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

/** Whether [url] is the output of [redactUrlCredentials] for a URL that carried a credential. */
fun hasRedactedCredentials(url: String): Boolean = "://$REDACTED_USERINFO" in url
