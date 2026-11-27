package com.rustyrazorblade.easydblab.configuration.profile

/**
 * What the user can do about a [ProfileFileException], so every report gives advice that works.
 *
 * `profile setup` reads the profile's raw values (`ProfileStore.loadForSetup`), so it can repair a
 * file that parses as YAML but holds a value that does not decode. It cannot repair a file it cannot
 * read or parse, an unknown key (it stops at the same key), or a file it cannot write or lock: naming
 * setup for those would send the user in a circle.
 */
enum class ProfileFileRemedy {
    /** A value does not decode, or the earlier file has no credentials: `profile setup` repairs it. */
    RUN_SETUP,

    /** An unknown key: setup stops at it too, so the user fixes or removes the key. */
    FIX_KEY,

    /** The file cannot be read, parsed, written or locked: setup fails the same way, so the user fixes the file. */
    FIX_FILE,

    /**
     * The profile directory is on a file system that cannot make a file owner-only. Nothing in the file
     * can fix it, so the user moves the profile directory to a file system with POSIX permissions.
     */
    POSIX_FILE_SYSTEM,
    ;

    companion object {
        /** What the user does about a [POSIX_FILE_SYSTEM] failure; the store's message and `profile show` both say it. */
        const val POSIX_FILE_SYSTEM_HINT = "Put the profile directory on a file system with POSIX permissions"
    }
}
