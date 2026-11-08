package com.rustyrazorblade.easydblab.compactorlease

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Empties the compactors' scratch directories when the lease is acquired, so a node that regains the
 * lease hours later does not replay stale work, such as old Tempo scheduler jobs. Each directory is
 * kept, because it is a host-path mount, and created when missing. A symbolic link is removed, not
 * followed. The directories hold only the compactors' local working copies, never the owner's data:
 * every compacted object is in the store.
 *
 * @property dirs the scratch directories, as mounted in the lease container.
 */
class ScratchWipe(
    private val dirs: List<Path>,
) : Runnable {
    override fun run() {
        dirs.forEach { dir ->
            Files.createDirectories(dir)
            Files.list(dir).use { entries -> entries.toList() }.forEach(::delete)
        }
    }

    private fun delete(path: Path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(path).use { entries -> entries.toList() }.forEach(::delete)
        }
        Files.delete(path)
    }
}
