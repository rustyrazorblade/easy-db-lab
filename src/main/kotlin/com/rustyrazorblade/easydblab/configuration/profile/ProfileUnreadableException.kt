package com.rustyrazorblade.easydblab.configuration.profile

import java.io.File

/**
 * A profile file, or the profile's lock file, could not be read, decoded or opened.
 *
 * It carries the [file] that failed, so a report can name it: with no `profile.yaml` the failing
 * file is the earlier `settings.yaml`, or the lock file the conversion takes. The message names the
 * file and the reason and never holds the file's text, which can hold credentials.
 */
class ProfileUnreadableException(
    val file: File,
    message: String,
    cause: Throwable?,
) : IllegalStateException(message, cause)
