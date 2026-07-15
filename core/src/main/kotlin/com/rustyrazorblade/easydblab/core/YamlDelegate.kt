package com.rustyrazorblade.easydblab.core

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlin.reflect.KProperty

/**
 * Making yaml mapping easy
 * Usage example:
 *
 * val yaml : ObjectMapper by YamlDelegate
 */
class YamlDelegate(val ignoreUnknown : Boolean = false) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>) : ObjectMapper {
        if(ignoreUnknown) {
            return yaml.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
        return yaml
    }

    companion object {
        val yaml = ObjectMapper(YAMLFactory()).registerKotlinModule()
    }
}

