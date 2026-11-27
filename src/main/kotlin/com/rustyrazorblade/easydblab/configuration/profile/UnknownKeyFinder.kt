package com.rustyrazorblade.easydblab.configuration.profile

import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
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
 * [parentPath] is the dotted path of the map that holds it; every segment is a declared field name,
 * so it is safe to show. [name] is file text and could be a pasted secret, so the caller decides
 * whether to show it. [line] and [column] are where the key starts.
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
 * a bad key.
 *
 * It names no destination type: an open polymorphic field (the destination) resolves its subclass
 * from [serializers] by the [typeKey] value, a sealed one (the AWS credentials) from its own
 * descriptor. A map whose type is unknown is skipped, because the decode reports the unknown type.
 * Lists, maps of strings and scalars are not checked.
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
    ): UnknownKey? = find(node, descriptor, path = emptyList())

    private fun find(
        node: YamlNode,
        descriptor: SerialDescriptor,
        path: List<String>,
    ): UnknownKey? {
        val map = node as? YamlMap ?: return null
        val declared = declaredBy(descriptor.nonNullOriginal, map) ?: return null
        val polymorphic = descriptor.nonNullOriginal.kind is PolymorphicKind
        return map.entries.entries.firstNotNullOfOrNull { (key, value) ->
            val index = declared.getElementIndex(key.content)
            when {
                polymorphic && key.content == typeKey -> null
                index == CompositeDecoder.UNKNOWN_NAME ->
                    UnknownKey(path.joinToString("."), key.content, declared.elementNames.toList(), key.location.line, key.location.column)
                else -> find(value, declared.getElementDescriptor(index), path + key.content)
            }
        }
    }

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
