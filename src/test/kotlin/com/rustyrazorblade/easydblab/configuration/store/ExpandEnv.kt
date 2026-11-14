package com.rustyrazorblade.easydblab.configuration.store

import org.apache.commons.text.StringSubstitutor

/**
 * Expands `${VAR}` placeholders in a config file the way the backends' `-config.expand-env=true`
 * flag does at runtime.
 *
 * Loki, Mimir and Tempo ship their YAML verbatim and let the process substitute `${VAR}` from the
 * environment. The golden tests render the config through the production code and then expand it
 * here, so they compare the exact text a running backend would read.
 */
object ExpandEnv {
    /** [template] with every `${VAR}` replaced by its value in [env]. */
    fun expand(
        template: String,
        env: Map<String, String>,
    ): String = StringSubstitutor(env).replace(template)
}
