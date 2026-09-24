package com.rustyrazorblade.easydblab.services

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Which lifecycle phases of a kit refuse to run over a copy of the kit that is already there.
 *
 * `install` refuses when the kit's scaffold directory already exists; `start` refuses when the
 * kit's runtime finds its workload in the cluster, and a guarded `start` also makes `stop` wait for
 * that workload to leave. Declared in `kit.yaml` as `collision-check`, either a boolean or a map of
 * phase to boolean.
 */
@Serializable(with = CollisionCheckSerializer::class)
data class CollisionCheck(
    val phases: Set<String>,
) {
    init {
        require(phases.all { it in GUARDABLE_PHASES }) { unguardableMessage(phases) }
    }

    /** Whether [phase] refuses to run over an existing copy of the kit. */
    fun guards(phase: String): Boolean = phase in phases

    companion object {
        /** Why [phases] cannot be collision-checked: names the ones that have no collision check. */
        fun unguardableMessage(phases: Set<String>): String =
            "collision-check: phase(s) ${(phases - GUARDABLE_PHASES).joinToString()} have no collision check; " +
                "only ${GUARDABLE_PHASES.joinToString()} can be guarded"

        /** The phases a collision check exists for. */
        val GUARDABLE_PHASES: Set<String> = setOf(Constants.Kit.PHASE_INSTALL, Constants.Kit.PHASE_START)

        /** `collision-check: false`, and the default: nothing is guarded. */
        val NONE = CollisionCheck(emptySet())

        /** `collision-check: true`: a second `install` and a `start` while running are refused. */
        val ENABLED = CollisionCheck(GUARDABLE_PHASES)
    }
}

/**
 * Reads `collision-check` from `kit.yaml`: a boolean (`true` is [CollisionCheck.ENABLED]) or a map
 * of phase to boolean, which guards the phases set to `true`. A phase with no collision check, or
 * a value that is not a boolean, is rejected. Writes the boolean when the value is one, else the map.
 */
object CollisionCheckSerializer : KSerializer<CollisionCheck> {
    private val phaseMapSerializer = MapSerializer(String.serializer(), Boolean.serializer())

    // A contextual descriptor, as kaml's own YamlNode has: kaml then hands this serializer whatever
    // node is there, where a BOOLEAN descriptor would make it reject a map before we see it.
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        SerialDescriptor("com.rustyrazorblade.easydblab.services.CollisionCheck", YamlNode.serializer().descriptor)

    override fun deserialize(decoder: Decoder): CollisionCheck {
        val input = decoder as? YamlInput ?: throw SerializationException("collision-check can only be read from kit.yaml")
        return when (val node = input.node) {
            is YamlScalar -> if (node.booleanValue("collision-check")) CollisionCheck.ENABLED else CollisionCheck.NONE
            is YamlMap -> fromPhaseMap(node)
            else -> throw SerializationException("collision-check must be a boolean or a map of phase to boolean")
        }
    }

    private fun fromPhaseMap(map: YamlMap): CollisionCheck {
        val guarded =
            map.entries
                .filter { (phase, value) ->
                    val flag = value as? YamlScalar ?: throw SerializationException("collision-check.${phase.content} must be a boolean")
                    flag.booleanValue("collision-check.${phase.content}")
                }.keys
                .map { it.content }
                .toSet()
        val declared =
            map.entries.keys
                .map { it.content }
                .toSet()
        if (!CollisionCheck.GUARDABLE_PHASES.containsAll(declared)) {
            throw SerializationException(CollisionCheck.unguardableMessage(declared))
        }
        return CollisionCheck(guarded)
    }

    private fun YamlScalar.booleanValue(field: String): Boolean =
        when (content.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw SerializationException("$field must be true or false, not '$content'")
        }

    override fun serialize(
        encoder: Encoder,
        value: CollisionCheck,
    ) {
        when (value) {
            CollisionCheck.ENABLED -> encoder.encodeBoolean(true)
            CollisionCheck.NONE -> encoder.encodeBoolean(false)
            else -> encoder.encodeSerializableValue(phaseMapSerializer, CollisionCheck.GUARDABLE_PHASES.associateWith { value.guards(it) })
        }
    }
}
