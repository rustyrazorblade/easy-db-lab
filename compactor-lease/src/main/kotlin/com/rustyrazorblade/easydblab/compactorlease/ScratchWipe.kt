package com.rustyrazorblade.easydblab.compactorlease

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Empties the compactors' scratch directories when the lease is acquired, so a node that regains the
 * lease hours later does not replay stale work, such as old Tempo scheduler jobs. Each directory is
 * kept, because it is a host-path mount, and created when missing. A symbolic link is removed, not
 * followed. The directories hold only the compactors' local working copies, never the owner's data:
 * every compacted object is in the store. An I/O failure throws, so the caller keeps its gate closed.
 *
 * @property dirs the scratch directories, as mounted in the lease container.
 */
class ScratchWipe(
    private val dirs: List<Path>,
) : Runnable {
    override fun run() {
        dirs.forEach { dir ->
            Files.createDirectories(dir)
            Files.walkFileTree(dir, ContentsDeleter(dir))
        }
    }

    /**
     * Deletes everything under [root] but keeps [root]. Without `FOLLOW_LINKS` the walk reports a
     * symbolic link, even one to a directory, to [visitFile], so the link itself is deleted.
     */
    private class ContentsDeleter(
        private val root: Path,
    ) : SimpleFileVisitor<Path>() {
        override fun visitFile(
            file: Path,
            attrs: BasicFileAttributes,
        ): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(
            dir: Path,
            exc: IOException?,
        ): FileVisitResult {
            if (exc != null) throw exc
            if (dir != root) Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    }
}
