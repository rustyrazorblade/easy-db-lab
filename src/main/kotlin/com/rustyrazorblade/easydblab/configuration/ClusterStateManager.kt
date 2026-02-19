package com.rustyrazorblade.easydblab.configuration

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.time.Instant

/**
 * Manages persistence of ClusterState to/from disk.
 *
 * This separates the data (ClusterState) from persistence concerns,
 * making ClusterState a pure data class that's easier to test.
 *
 * @param stateFile The file to read/write cluster state. Defaults to "state.json" in current directory.
 */
class ClusterStateManager(
    private val stateFile: File = File(CLUSTER_STATE),
) {
    private val mapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .apply {
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
                configure(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, false)
                disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            }

    /**
     * Load cluster state from the configured file.
     * @throws Exception if file doesn't exist or can't be parsed
     */
    fun load(): ClusterState = mapper.readValue(stateFile, ClusterState::class.java)

    /**
     * Save cluster state to the configured file.
     */
    fun save(state: ClusterState) = mapper.writerWithDefaultPrettyPrinter().writeValue(stateFile, state)

    /**
     * Check if the state file exists.
     */
    fun exists(): Boolean = stateFile.exists()

    /**
     * Load, update hosts, and save in a single operation.
     */
    fun updateHosts(hosts: Map<ServerType, List<ClusterHost>>) {
        val state = load()
        state.updateHosts(hosts)
        save(state)
    }

    /**
     * Load, update EMR cluster, and save in a single operation.
     */
    fun updateEmrCluster(emrCluster: EMRClusterState?) {
        val state = load()
        state.updateEmrCluster(emrCluster)
        save(state)
    }

    /**
     * Load, update infrastructure, and save in a single operation.
     */
    fun updateInfrastructure(infrastructure: InfrastructureState?) {
        val state = load()
        state.updateInfrastructure(infrastructure)
        save(state)
    }

    /**
     * Load, mark infrastructure as UP, and save.
     */
    fun markInfrastructureUp() {
        val state = load()
        state.markInfrastructureUp()
        save(state)
    }

    /**
     * Load, mark infrastructure as DOWN, and save.
     */
    fun markInfrastructureDown() {
        val state = load()
        state.markInfrastructureDown()
        save(state)
    }

    /**
     * Atomically increment the stress job counter and return the new value.
     * Used for unique job naming and port assignment.
     */
    fun incrementStressJobCounter(): Int {
        val state = load()
        state.stressJobCounter++
        state.lastAccessedAt = Instant.now()
        save(state)
        return state.stressJobCounter
    }
}
