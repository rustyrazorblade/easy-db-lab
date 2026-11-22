package com.rustyrazorblade.easydblab

/**
 * The one check for a kit name typed by a user or read back from a kit's recorded args.
 *
 * A kit name becomes a directory name directly under the workspace, so it holds only letters,
 * digits, `-` and `_` and never starts with a dash: no separator, no `..`, no space and nothing a
 * shell would split. Every place that turns a kit name, kit-ref target or extension into a
 * workspace path checks it here, so all of them refuse the same names with the same message.
 *
 * @return [name], unchanged
 * @throws IllegalArgumentException naming the value when it is not a kit name
 */
fun requireKitName(name: String): String {
    require(Constants.Kit.NAME_PATTERN.matches(name)) {
        "Invalid kit name '$name': use letters, digits, hyphens or underscores, and start with a letter or digit"
    }
    return name
}

/**
 * True when [listing], the names in the workspace directory, holds [name] exactly, case included.
 * A case-insensitive file system opens `kafka` for `KAFKA`, so a name is matched against the
 * listing and never by opening the path.
 */
fun listsExactly(
    listing: Collection<String>,
    name: String,
): Boolean = name in listing
