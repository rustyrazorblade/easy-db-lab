package com.rustyrazorblade.easydblab

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar

/**
 * Reads rendered configuration files structurally, so a test asserts what a key is set to rather
 * than that some text appears somewhere in the file.
 */
object YamlTestSupport {
    /** The node at [path] in [yaml], or null when any key on the way is missing. */
    fun nodeAt(
        yaml: String,
        vararg path: String,
    ): YamlNode? {
        var node: YamlNode? = Yaml.default.parseToYamlNode(yaml)
        for (key in path) {
            node = (node as? YamlMap)?.get<YamlNode>(key) ?: return null
        }
        return node
    }

    /** The scalar at [path] in [yaml], or null when it is missing or not a scalar. */
    fun scalarAt(
        yaml: String,
        vararg path: String,
    ): String? = (nodeAt(yaml, *path) as? YamlScalar)?.content

    /** The keys of the map at [path] in [yaml]; empty when it is missing or not a map. */
    fun keysAt(
        yaml: String,
        vararg path: String,
    ): Set<String> =
        (nodeAt(yaml, *path) as? YamlMap)
            ?.entries
            ?.keys
            ?.map { it.content }
            ?.toSet()
            .orEmpty()

    /** The scalars of the list at [path] in [yaml]; empty when it is missing or not a list. */
    fun listAt(
        yaml: String,
        vararg path: String,
    ): List<String> =
        (nodeAt(yaml, *path) as? YamlList)
            ?.items
            ?.mapNotNull { (it as? YamlScalar)?.content }
            .orEmpty()
}
