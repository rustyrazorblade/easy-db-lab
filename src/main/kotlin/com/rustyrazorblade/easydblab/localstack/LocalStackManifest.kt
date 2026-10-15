package com.rustyrazorblade.easydblab.localstack

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The record a stack directory keeps of what the tool wrote there (`.easy-db-lab-local-stack.json`).
 *
 * Its presence marks the directory as the tool's, so a re-run may rewrite the files it lists, and
 * only those. The checksums tell a re-run which owned files the user edited. `local dashboards`
 * reads the Grafana port from it.
 *
 * @property toolVersion the easy-db-lab version that wrote the stack.
 * @property mode `live` or `offline` ([LocalStackSpec.Mode.manifestName]).
 * @property homeTenant the tenant of the stable datasources.
 * @property tenants every tenant Grafana got datasources for, sorted, the home tenant included.
 * @property grafanaPort Grafana's port on `127.0.0.1`.
 * @property files the sha256 (hex) of each owned file as written, by path relative to the directory.
 */
@Serializable
data class LocalStackManifest(
    val toolVersion: String,
    val mode: String,
    val homeTenant: String,
    val tenants: List<String>,
    val grafanaPort: Int,
    val files: Map<String, String>,
) {
    /** This manifest as JSON. */
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            }

        /** Reads the manifest at [file]. */
        fun read(file: File): LocalStackManifest = JSON.decodeFromString(serializer(), file.readText())
    }
}
