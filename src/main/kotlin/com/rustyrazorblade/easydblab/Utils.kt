package com.rustyrazorblade.easydblab

import org.apache.commons.io.IOUtils
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Resolves the easy-db-lab user directory.
 * Checks EASY_DB_LAB_USER_DIR environment variable first, falls back to ~/.easy-db-lab
 */
fun resolveEasyDbLabUserDir(): File {
    val envPath = System.getenv(Constants.Environment.USER_DIR)
    return if (envPath != null) {
        File(envPath)
    } else {
        File(System.getProperty("user.home"), ".easy-db-lab")
    }
}

object Utils {
    fun inputstreamToTempFile(
        inputStream: InputStream,
        prefix: String,
        directory: String,
    ): File {
        val tempFile = File.createTempFile(prefix, "", File(directory))
        tempFile.deleteOnExit()

        val outputStream = FileOutputStream(tempFile)

        IOUtils.copy(inputStream, outputStream)
        outputStream.flush()
        outputStream.close()

        return tempFile
    }

    @Deprecated(message = "Please use ResourceFile")
    fun resourceToTempFile(
        resourcePath: String,
        directory: String,
    ): File {
        val resourceName = File(resourcePath).name
        val resourceStream = this::class.java.getResourceAsStream(resourcePath)
        return Utils.inputstreamToTempFile(resourceStream, "${resourceName}_", directory)
    }

    fun prompt(
        question: String,
        default: String,
        secret: Boolean = false,
    ): String {
        print("$question [$default]: ")

        var line: String =
            if (secret) {
                System.console()?.readPassword()?.let { String(it) }
                    ?: error("Unable to read password from console")
            } else {
                (readlnOrNull() ?: default).trim()
            }

        if (line.equals("")) {
            line = default
        }

        return line
    }
}
