package com.rustyrazorblade.easydblab.localstack

import com.charleskorn.kaml.Location
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar

/**
 * One typed change to a parsed YAML document, addressed by the chain of map keys (and list indices,
 * such as the `0` of `schema_config.configs.0.object_store`) that leads to it.
 *
 * The local stack derives a backend's configuration from the cluster's own resource file this way,
 * so the derived file can never drift from the cluster's except where a patch says so, and no YAML is
 * ever assembled from strings.
 */
sealed interface YamlPatch {
    /** The map keys, or the indices of list items, from the document root to the changed entry. */
    val path: List<String>

    /**
     * Sets the scalar at [path], creating the maps on the way. kaml writes a value that reads as a
     * boolean or a number as one, so `true` stays a YAML boolean.
     */
    data class Set(
        override val path: List<String>,
        val value: String,
    ) : YamlPatch

    /** Removes the entry at [path], if there is one, so the program's own default applies. */
    data class Remove(
        override val path: List<String>,
    ) : YamlPatch

    companion object {
        /** `a.b.c` as a path. */
        fun set(
            dottedPath: String,
            value: String,
        ): YamlPatch = Set(dottedPath.split('.'), value)

        /** `a.b.c` as a path. */
        fun remove(dottedPath: String): YamlPatch = Remove(dottedPath.split('.'))
    }
}

/**
 * Parses a YAML document, applies [YamlPatch]es to its tree and writes it back. Comments are not kept;
 * every value keeps its type (booleans, numbers, strings, lists and maps).
 */
object YamlDocument {
    /** Generated nodes have no place in a source file; kaml uses locations only in error messages. */
    private val GENERATED = Location(1, 1)

    /** Plain scalars where YAML allows them; kaml still quotes a string that would read as another type. */
    private val yaml = Yaml(configuration = YamlConfiguration(singleLineStringStyle = SingleLineStringStyle.Plain))

    /**
     * [text] with every patch applied in order, ending with a newline as a text file does (kaml writes
     * none). [text] must be a map at its root.
     */
    fun patch(
        text: String,
        patches: List<YamlPatch>,
    ): String {
        val root = yaml.parseToYamlNode(text) as? YamlMap ?: throw IllegalArgumentException("The document is not a map at its root")
        val patched = patches.fold(root) { map, patch -> apply(map, patch, patch.path) }
        return yaml.encodeToString(YamlNode.serializer(), patched).trimEnd('\n') + "\n"
    }

    private fun apply(
        map: YamlMap,
        patch: YamlPatch,
        path: List<String>,
    ): YamlMap {
        val key = path.first()
        val existing = map.entries.entries.firstOrNull { it.key.content == key }
        val valuePath = map.path.withMapElementKey(key, GENERATED).withMapElementValue(GENERATED)
        val replacement: YamlNode? =
            when {
                path.size > 1 && existing?.value is YamlList -> applyToItem(existing.value as YamlList, patch, path.drop(1))
                path.size > 1 -> {
                    val child = existing?.value ?: YamlMap(emptyMap(), valuePath)
                    require(child is YamlMap) { "${patch.path.joinToString(".")}: $key is not a map" }
                    if (patch is YamlPatch.Remove && existing == null) return map
                    apply(child, patch, path.drop(1))
                }
                patch is YamlPatch.Set -> YamlScalar(patch.value, valuePath)
                else -> null
            }
        val entries = LinkedHashMap<YamlScalar, YamlNode>(map.entries)
        val entryKey = existing?.key ?: YamlScalar(key, map.path.withMapElementKey(key, GENERATED))
        if (replacement == null) entries.remove(entryKey) else entries[entryKey] = replacement
        return YamlMap(entries, map.path)
    }

    /** Applies [patch] inside the map at index `path.first()` of [list]; the index must exist. */
    private fun applyToItem(
        list: YamlList,
        patch: YamlPatch,
        path: List<String>,
    ): YamlList {
        val index = path.first().toIntOrNull()
        val item = index?.let { list.items.getOrNull(it) }
        require(item is YamlMap && path.size > 1) { "${patch.path.joinToString(".")}: ${path.first()} is not a map in the list" }
        return YamlList(list.items.mapIndexed { n, node -> if (n == index) apply(item, patch, path.drop(1)) else node }, list.path)
    }
}
