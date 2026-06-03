package com.rustyrazorblade.easydblab.configuration

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.rustyrazorblade.easydblab.exceptions.DuplicateVersionException
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class CassandraVersion(
    val version: String,
    val java: String,
    val python: String,
    @JsonIgnoreProperties(ignoreUnknown = true)
    val axonops: String? = null,
    @JsonIgnoreProperties(ignoreUnknown = true)
    @param:JsonProperty("jvm_options")
    val jvmOptions: String?,
    @JsonIgnoreProperties(ignoreUnknown = true)
    @param:JsonProperty("ant_flags")
    val antFlags: String? = null,
    @JsonIgnoreProperties(ignoreUnknown = true)
    val url: String? = null,
    @JsonIgnoreProperties(ignoreUnknown = true)
    val branch: String? = null,
    @JsonIgnoreProperties(ignoreUnknown = true)
    @param:JsonProperty("java_build")
    val javaBuild: String? = null,
    @JsonIgnoreProperties(ignoreUnknown = true)
    @param:JsonProperty("jvm_config")
    val jvmConfig: String? = null,
) {
    companion object {
        private val objectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
        private val logger = KotlinLogging.logger {}

        fun loadFromFile(filePath: Path): List<CassandraVersion> {
            val fileContent = Files.readString(filePath)
            return objectMapper.readValue(
                fileContent,
                objectMapper.typeFactory.constructCollectionType(List::class.java, CassandraVersion::class.java),
            )
        }

        fun loadFromMainAndExtras(
            mainFilePath: Path,
            extrasDirectoryPath: Path,
        ): List<CassandraVersion> {
            // Load main file
            val mainVersions = loadFromFile(mainFilePath).toMutableList()

            // Load each file in the extras directory
            if (extrasDirectoryPath.exists() && extrasDirectoryPath.isDirectory()) {
                val listDirectoryEntries = extrasDirectoryPath.listDirectoryEntries("*.yaml")
                logger.info { "Loading from  ${listDirectoryEntries.size} extra potential files" }
                for (file in listDirectoryEntries) {
                    logger.info { "Loading additional cassandra_versions file: $file" }
                    val extraVersions = loadFromFile(file)
                    logger.info { "Adding ${extraVersions.size} versions" }
                    mainVersions.addAll(extraVersions)
                }
            } else {
                logger.info { "Nothing to load" }
            }

            // Remove duplicates based on the version field
            val tmp = mainVersions.distinctBy { it.version }
            if (tmp.size != mainVersions.size) {
                val duplicates =
                    mainVersions
                        .groupBy { it.version }
                        .filter { it.value.size > 1 }
                        .keys
                throw DuplicateVersionException(duplicates)
            }
            return mainVersions
        }

        fun write(
            versions: List<CassandraVersion>,
            outputStream: OutputStream,
        ) {
            objectMapper.writeValue(outputStream, versions)
        }

        fun write(
            versions: List<CassandraVersion>,
            outputFile: File,
        ) {
            objectMapper.writeValue(outputFile, versions)
        }
    }
}
