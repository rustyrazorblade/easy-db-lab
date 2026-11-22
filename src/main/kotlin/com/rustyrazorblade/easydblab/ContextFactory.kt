package com.rustyrazorblade.easydblab

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Factory for creating and caching Context instances. Manages Context lifecycle with key-based
 * caching to avoid recreating instances.
 *
 * [workingDirectory] is the workspace every context it builds resolves workspace files against.
 * The CLI passes [processStartDirectory]; nothing else reads the process's current directory.
 */
class ContextFactory(
    private val baseDirectory: File,
    private val workingDirectory: File,
    private val appHome: String? = System.getProperty("easydblab.apphome"),
) {
    companion object {
        /** The directory the CLI was started in: the workspace. The one read of `user.dir` in the tool. */
        fun processStartDirectory(): File = File(System.getProperty("user.dir")).absoluteFile
    }

    private val contextCache = ConcurrentHashMap<String, Context>()

    /**
     * Get or create a Context instance for the given key. Contexts are cached to ensure the same
     * instance is returned for repeated calls with the same key.
     *
     * @param key Unique identifier for the context (e.g., profile name, environment)
     * @return Cached or newly created Context instance
     */
    fun getContext(key: String): Context = contextCache.getOrPut(key) { createContext(key) }

    /**
     * Get the default context (key = "default"). Convenience method for the most common use case.
     */
    fun getDefault(): Context = getContext("default")

    private fun createContext(key: String): Context {
        val contextDir =
            if (key == "default") {
                baseDirectory
            } else {
                File(baseDirectory, key)
            }
        return Context(contextDir, workingDirectory = workingDirectory, appHome = appHome)
    }

    /**
     * Clear a specific context from the cache. Useful for testing or when context needs to be
     * reloaded.
     */
    fun clearContext(key: String) {
        contextCache.remove(key)
    }

    /** Clear all cached contexts. */
    fun clearAll() {
        contextCache.clear()
    }
}
