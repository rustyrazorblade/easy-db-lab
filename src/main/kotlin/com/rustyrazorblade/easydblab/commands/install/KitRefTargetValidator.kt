package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.services.KitArgSpec
import com.rustyrazorblade.easydblab.services.KitConfig

/**
 * Checks, before `kit install` writes anything, that the kit a kit-ref arg names provides what
 * the installing kit needs: the arg's `capability` and every type in its `endpoints` list.
 *
 * It is pure: [lookup] reads the target's `kit.yaml`, so the rules are tested without a
 * workspace. An arg with no capability and no endpoints never loads the target.
 */
class KitRefTargetValidator(
    private val lookup: (targetName: String) -> TargetLookup,
) {
    /** What [lookup] found for a target kit name. */
    sealed interface TargetLookup {
        /** The target is installed; [config] is its parsed `kit.yaml`. */
        data class Installed(
            val config: KitConfig,
        ) : TargetLookup

        /** The target has no `kit.yaml` at [expectedPath]. */
        data class NotInstalled(
            val expectedPath: String,
        ) : TargetLookup
    }

    /**
     * Fails when [targetName] is not installed, lacks [kitRefArg]'s capability, or lacks any of
     * its endpoint types. Every missing endpoint type is named in one message.
     *
     * @throws IllegalStateException naming the target, what it lacks and [kitName].
     */
    fun validate(
        kitName: String,
        kitRefArg: KitArgSpec,
        targetName: String,
    ) {
        if (kitRefArg.capability.isBlank() && kitRefArg.endpoints.isEmpty()) return

        val target =
            when (val found = lookup(targetName)) {
                is TargetLookup.Installed -> found.config
                is TargetLookup.NotInstalled ->
                    error("Target kit '$targetName' is not installed (expected ${found.expectedPath})")
            }

        val capability = kitRefArg.capability
        check(capability.isBlank() || target.capabilities.any { it.type == capability }) {
            "Target kit '$targetName' does not have the '$capability' capability required by $kitName"
        }

        val declared = target.endpoints.map { it.type }.toSet()
        val missing = kitRefArg.endpoints.filterNot { it in declared }.map { "'${it.name.lowercase()}'" }
        check(missing.isEmpty()) {
            "Target kit '$targetName' does not have the ${describe(missing)} required by $kitName"
        }
    }

    private fun describe(quotedTypes: List<String>): String =
        when (quotedTypes.size) {
            1 -> "${quotedTypes.single()} endpoint"
            else -> "${quotedTypes.dropLast(1).joinToString(", ")} and ${quotedTypes.last()} endpoints"
        }
}
