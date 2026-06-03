package com.rustyrazorblade.easydblab.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * Provides User configuration by loading and caching it from the YAML file.
 * Responsible for User persistence operations (load/save/cache).
 * Setup logic is handled by the SetupProfile command.
 */
class UserConfigProvider(
    private val profileDir: File,
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val yaml: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    private val userConfigFile = File(profileDir, "settings.yaml")

    /**
     * SSH key path is always ${profileDir}/secret.pem
     * This is where generateAwsKeyPair() writes the private key.
     */
    val sshKeyPath: String
        get() = File(profileDir, "secret.pem").absolutePath

    /**
     * Cached user configuration instance
     */
    private var cachedUserConfig: User? = null

    /**
     * Checks if user configuration file exists and is set up.
     *
     * @return true if the settings.yaml file exists, false otherwise
     */
    fun isSetup(): Boolean = userConfigFile.exists()

    /**
     * Gets the User configuration, loading it from file if not already cached.
     * Throws an exception if the settings.yaml file doesn't exist.
     * Use SetupProfile command to create initial configuration.
     *
     * @return The User configuration
     * @throws IllegalStateException if settings.yaml doesn't exist
     */
    fun getUserConfig(): User = cachedUserConfig ?: loadUserConfig().also { cachedUserConfig = it }

    /**
     * Loads existing YAML as Map or returns empty map if file doesn't exist.
     * Public so SetupProfile command can use it to check for existing values.
     */
    fun loadExistingConfig(): Map<String, Any> =
        if (userConfigFile.exists()) {
            @Suppress("UNCHECKED_CAST")
            yaml.readValue(userConfigFile, Map::class.java) as Map<String, Any>
        } else {
            emptyMap()
        }

    /**
     * Loads the user configuration from the YAML file.
     * Throws an exception if the file doesn't exist.
     *
     * @return The User configuration
     * @throws IllegalStateException if settings.yaml doesn't exist
     */
    private fun loadUserConfig(): User {
        log.debug { "Loading user config from $userConfigFile" }

        if (!userConfigFile.exists()) {
            error(
                "User configuration file not found: $userConfigFile\n" +
                    "Please run 'easy-db-lab setup-profile' to create your profile.",
            )
        }

        return yaml.readValue(userConfigFile)
    }

    /**
     * Saves the provided User configuration to the YAML file and updates the cache.
     *
     * @param user The User configuration to save
     */
    fun saveUserConfig(user: User) {
        log.debug { "Saving user config to $userConfigFile" }
        yaml.writeValue(userConfigFile, user)
        cachedUserConfig = user
    }

    /**
     * Clears the cached user configuration, forcing it to be reloaded on next access.
     * Useful for tests or when the configuration file has been modified externally.
     */
    fun clearCache() {
        cachedUserConfig = null
    }
}
