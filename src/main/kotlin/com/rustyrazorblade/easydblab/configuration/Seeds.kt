package com.rustyrazorblade.easydblab.configuration

import org.apache.commons.io.IOUtils
import java.io.InputStream
import java.io.StringWriter
import java.nio.charset.Charset

data class Seeds(
    val seeds: List<String>,
) {
    companion object {
        fun open(stream: InputStream): Seeds {
            val buf = StringWriter()
            IOUtils.copy(stream, buf, Charset.defaultCharset())
            val seeds = buf.toString().split("\n")
            return Seeds(seeds)
        }
    }

    override fun toString(): String = seeds.joinToString(",")
}
