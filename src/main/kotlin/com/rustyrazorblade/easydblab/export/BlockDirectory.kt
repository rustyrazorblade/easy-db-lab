package com.rustyrazorblade.easydblab.export

import java.io.File
import java.time.Instant

/**
 * One block directory of a tenant, as it is in the directory an index is written for.
 *
 * @property id the directory's name: the block's ULID (Mimir) or UUID (Tempo).
 * @property meta the block's `meta.json`, or null when the directory has none.
 * @property metaWrittenAt when `meta.json` was written; Mimir records it as the block's upload time.
 */
class StagedBlock(
    val id: String,
    val meta: ByteArray?,
    val metaWrittenAt: Instant,
)

/**
 * A tenant directory that a Mimir bucket index or a Tempo tenant index is written for, read as it
 * is: every subdirectory with what its `meta.json` says. An index is built from this, never from a
 * list of what was meant to be copied, so it lists exactly the blocks that are there.
 */
interface BlockDirectory {
    /** Where the directory is, for messages. */
    val description: String

    /** Every subdirectory, by name. */
    fun blocks(): Map<String, StagedBlock>
}

/**
 * A tenant directory on disk.
 *
 * @property directory the tenant directory, `<root>/<tenant>`.
 */
class FileBlockDirectory(
    private val directory: File,
) : BlockDirectory {
    override val description: String get() = directory.path

    override fun blocks(): Map<String, StagedBlock> =
        directory
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .associate { block ->
                val meta = File(block, META_FILE)
                block.name to
                    StagedBlock(
                        id = block.name,
                        meta = meta.takeIf { it.isFile }?.readBytes(),
                        metaWrittenAt = Instant.ofEpochMilli(meta.lastModified()),
                    )
            }

    private companion object {
        const val META_FILE = "meta.json"
    }
}

/** An index does not list exactly the blocks of the directory it is for, or a block there cannot be indexed. */
class IndexMismatchException(
    message: String,
) : IllegalStateException(message)

/**
 * Fails unless [listed] names exactly the [blocks] of [directory], each once.
 *
 * @param what the index, for the message.
 */
internal fun requireExactBlocks(
    what: String,
    listed: List<String>,
    blocks: Set<String>,
    directory: BlockDirectory,
) {
    val leftOut = blocks - listed.toSet()
    val absent = listed.toSet() - blocks
    val twice = listed.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (leftOut.isEmpty() && absent.isEmpty() && twice.isEmpty()) return
    val problems =
        listOfNotNull(
            leftOut.takeIf { it.isNotEmpty() }?.let { "leaves out ${it.sorted().joinToString(", ")}, which are there" },
            absent.takeIf { it.isNotEmpty() }?.let { "lists ${it.sorted().joinToString(", ")}, which are not in it" },
            twice.takeIf { it.isNotEmpty() }?.let { "lists ${it.sorted().joinToString(", ")} more than once" },
        )
    throw IndexMismatchException("The $what for ${directory.description} ${problems.joinToString("; ")}")
}

/** The blocks of [directory] other than [notBlocks], failing on any with no `meta.json`. */
internal fun indexableBlocks(
    what: String,
    directory: BlockDirectory,
    notBlocks: Set<String>,
): List<StagedBlock> {
    val blocks = directory.blocks().filterKeys { it !in notBlocks }.values.sortedBy { it.id }
    val noMeta = blocks.filter { it.meta == null }.map { it.id }
    if (noMeta.isNotEmpty()) {
        throw IndexMismatchException(
            "Cannot write the $what for ${directory.description}: these blocks have no meta.json: ${noMeta.joinToString(", ")}",
        )
    }
    return blocks
}
