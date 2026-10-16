package com.rustyrazorblade.easydblab.localstack

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A docker compose file, as the typed subset of the Compose Specification the local stacks use.
 *
 * It is written with kaml, never assembled from strings, and read back the same way in tests. It sets
 * no project `name`, so compose names the project after its directory and two stacks in two
 * directories never share containers.
 *
 * @property services every service, by name; the name is also its host name on the project network.
 */
@Serializable
data class ComposeFile(
    val services: Map<String, ComposeService>,
) {
    /** This file as YAML. */
    fun toYaml(): String = YAML.encodeToString(serializer(), this)

    companion object {
        /**
         * Unset fields are left out. Every string is double-quoted: compose refuses a bare number
         * where it wants a string (a command argument such as memcached's `2048`, a `user`), and a
         * quoted value can never read as a boolean or a number.
         */
        private val YAML = Yaml(configuration = YamlConfiguration(encodeDefaults = false))

        /** Reads a file written by [toYaml]. */
        fun fromYaml(text: String): ComposeFile = YAML.decodeFromString(serializer(), text)
    }
}

/**
 * One compose service.
 *
 * @property image the image, always a registered pin ([LocalImages]).
 * @property user the user the container runs as, where the image's own would not fit.
 * @property entrypoint replaces the image's entrypoint.
 * @property command the arguments.
 * @property environment the environment variables.
 * @property volumes the mounts, in long syntax.
 * @property ports published ports, `address:host:container`.
 * @property dependsOn the services this one waits for, and the condition it waits on.
 * @property healthcheck how compose decides the service is healthy.
 * @property restart the restart policy.
 * @property networkMode the container's network, `none` for one that must reach nothing.
 */
@Serializable
data class ComposeService(
    val image: String,
    val user: String? = null,
    val entrypoint: List<String>? = null,
    val command: List<String>? = null,
    val environment: Map<String, String>? = null,
    val volumes: List<ComposeMount>? = null,
    val ports: List<String>? = null,
    @SerialName("depends_on")
    val dependsOn: Map<String, ComposeDependency>? = null,
    val healthcheck: ComposeHealthcheck? = null,
    val restart: String? = null,
    @SerialName("network_mode")
    val networkMode: String? = null,
)

/**
 * A service mount in the long syntax.
 *
 * @property type `bind` for a host path, `image` for another image's filesystem.
 * @property source the host path (relative to the compose file, or under `~`) or the image.
 * @property target the path inside the container.
 * @property readOnly whether the container may write to it.
 */
@Serializable
data class ComposeMount(
    val type: String,
    val source: String,
    val target: String,
    @SerialName("read_only")
    val readOnly: Boolean? = null,
) {
    companion object {
        /** A bind mount of [source]. */
        fun bind(
            source: String,
            target: String,
            readOnly: Boolean,
        ): ComposeMount = ComposeMount("bind", source, target, readOnly)

        /** The filesystem of [image], mounted read-only at [target]. */
        fun image(
            image: String,
            target: String,
        ): ComposeMount = ComposeMount("image", image, target, readOnly = true)
    }
}

/**
 * What a service waits for in another before it starts.
 *
 * @property condition `service_started`, `service_healthy` or `service_completed_successfully`.
 */
@Serializable
data class ComposeDependency(
    val condition: String,
) {
    companion object {
        /** Waits only for the other service to start. */
        val STARTED = ComposeDependency("service_started")

        /** Waits for the other, one-shot, service to exit 0; a non-zero exit stops this one from starting. */
        val COMPLETED = ComposeDependency("service_completed_successfully")
    }
}

/**
 * A compose healthcheck.
 *
 * @property test the command, `CMD` first.
 * @property interval time between checks.
 * @property timeout time one check may take.
 * @property retries consecutive failures before the service is unhealthy.
 * @property startPeriod time after start during which a failure does not count.
 */
@Serializable
data class ComposeHealthcheck(
    val test: List<String>,
    val interval: String,
    val timeout: String,
    val retries: Int,
    @SerialName("start_period")
    val startPeriod: String,
)
