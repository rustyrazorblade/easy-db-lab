package com.rustyrazorblade.easydblab.configuration.profile

import java.io.File

/**
 * A profile file, or the profile's lock file, could not be read, decoded, written, opened or locked.
 *
 * It carries the [file] that failed, so a report can name it: with no `profile.yaml` the failing
 * file is the earlier `settings.yaml`, or the lock file the conversion takes.
 *
 * [message] is the full sentence for logs and other callers: it names the file and the reason, and
 * may tell the user how to recover. [reason] is only why it failed, with neither the path nor the
 * setup hint, so a report that prints the path and the hint itself does not say either twice.
 * Neither holds the file's text, which can hold credentials.
 */
class ProfileFileException(
    val file: File,
    val reason: String,
    message: String,
    cause: Throwable?,
) : IllegalStateException(message, cause)
