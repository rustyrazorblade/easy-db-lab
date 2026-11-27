package com.rustyrazorblade.easydblab.configuration.profile

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlTaggedNode
import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.descriptors.getPolymorphicDescriptors
import kotlinx.serialization.descriptors.nonNullOriginal
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.SerializersModule

/**
 * A key in the profile file that no field declares.
 *
 * [parentPath] is the path of the map that holds it, empty at the top level. Its segments are
 * declared field names joined by dots, a list item's index as `[i]` after its list, and
 * [Constants.ConfigPaths.PROFILE_MAP_KEY_SEGMENT] for a map entry, whose key is file text. So it holds
 * no file text and is safe to show. [name] is file text and could be a pasted secret, so the caller
 * decides whether to show it. [line] and [column] are where the key starts.
 */
internal data class UnknownKey(
    val parentPath: String,
    val name: String,
    val knownKeys: List<String>,
    val line: Int,
    val column: Int,
)

/**
 * Finds the first key in a parsed profile file that no field declares, without decoding a value.
 *
 * A strict decode stops at the first failure, so a bad value earlier in the file (an unknown SSH
 * transport, an unknown destination type) would hide a misspelled key after it, and setup would read
 * the misspelled field as blank and create a second bucket or key pair. This walk compares each
 * map's keys to the serializer's field names and never looks at a value, so a bad value cannot hide
 * a bad key. The strict decode behind it reports an unknown key with its name unredacted, so this
 * walk covers every shape a field can have and the decode never meets one.
 *
 * It names no destination type: an open polymorphic field (the destination) resolves its subclass
 * from [serializers] by the [typeKey] value, a sealed one (the AWS credentials) from its own
 * descriptor. A map whose type is unknown is skipped, because the decode reports the unknown type.
 * A list is walked item by item. A map field's keys are data, not field names, so they are never
 * reported; each of its values is walked. A tagged node is walked as the node inside the tag, as
 * kaml decodes it. Scalars hold no keys.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class UnknownKeyFinder(
    private val serializers: SerializersModule,
    private val typeKey: String,
) {
    /** The first unknown key under [node], in file order, read as [descriptor]; null when every key is declared. */
    fun find(
        node: YamlNode,
        descriptor: SerialDescriptor,
    ): UnknownKey? = find(node, descriptor.nonNullOriginal, path = "")

    private fun find(
        node: YamlNode,
        descriptor: SerialDescriptor,
        path: String,
    ): UnknownKey? =
        when {
            // kaml decodes through a tag (`key: !x`), so the walk must too.
            node is YamlTaggedNode -> find(node.innerNode, descriptor, path)
            descriptor.kind == StructureKind.LIST && node is YamlList -> inList(node, descriptor, path)
            descriptor.kind == StructureKind.MAP && node is YamlMap -> inMapValues(node, descriptor, path)
            node is YamlMap -> declaredBy(descriptor, node)?.let { inFields(node, it, descriptor.kind is PolymorphicKind, path) }
            else -> null
        }

    private fun inList(
        list: YamlList,
        descriptor: SerialDescriptor,
        path: String,
    ): UnknownKey? {
        val item = descriptor.getElementDescriptor(0).nonNullOriginal
        return list.items.withIndex().firstNotNullOfOrNull { (index, value) -> find(value, item, "$path[$index]") }
    }

    private fun inMapValues(
        map: YamlMap,
        descriptor: SerialDescriptor,
        path: String,
    ): UnknownKey? {
        val value = descriptor.getElementDescriptor(1).nonNullOriginal
        val entryPath = child(path, Constants.ConfigPaths.PROFILE_MAP_KEY_SEGMENT)
        return map.entries.values.firstNotNullOfOrNull { find(it, value, entryPath) }
    }

    private fun inFields(
        map: YamlMap,
        declared: SerialDescriptor,
        polymorphic: Boolean,
        path: String,
    ): UnknownKey? =
        map.entries.entries.firstNotNullOfOrNull { (key, value) ->
            val index = declared.getElementIndex(key.content)
            when {
                polymorphic && key.content == typeKey -> null
                index == CompositeDecoder.UNKNOWN_NAME ->
                    UnknownKey(path, key.content, declared.elementNames.toList(), key.location.line, key.location.column)
                else -> find(value, declared.getElementDescriptor(index).nonNullOriginal, child(path, key.content))
            }
        }

    private fun child(
        path: String,
        segment: String,
    ): String = if (path.isEmpty()) segment else "$path.$segment"

    /** The class whose fields [map] holds, or null when it is not a class or its type is unknown. */
    private fun declaredBy(
        descriptor: SerialDescriptor,
        map: YamlMap,
    ): SerialDescriptor? =
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> descriptor
            PolymorphicKind.OPEN -> subclass(serializers.getPolymorphicDescriptors(descriptor), map)
            PolymorphicKind.SEALED -> subclass(sealedSubclasses(descriptor), map)
            else -> null
        }

    /** A sealed descriptor's last element holds one element per subclass. */
    private fun sealedSubclasses(descriptor: SerialDescriptor): List<SerialDescriptor> =
        descriptor.elementDescriptors
            .last()
            .elementDescriptors
            .toList()

    private fun subclass(
        candidates: List<SerialDescriptor>,
        map: YamlMap,
    ): SerialDescriptor? {
        val type = map.getScalar(typeKey)?.content ?: return null
        return candidates.firstOrNull { it.serialName == type }
    }
}
