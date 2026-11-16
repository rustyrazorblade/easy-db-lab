package com.rustyrazorblade.easydblab.configuration.grafana

import java.io.File

/**
 * Every dashboard JSON file in the repository: the core tree under `dashboards/` and each kit's
 * `dashboards/` directory. The dashboard tests of both tiers read the same set from here, so a new
 * dashboard directory is picked up by every scan at once.
 */
object DashboardFiles {
    private const val CORE_ROOT = "dashboards"
    private const val KITS_ROOT = "src/main/resources/com/rustyrazorblade/easydblab/kits"

    /** The core dashboards and every kit's dashboards, sorted by path. */
    fun all(): List<File> {
        val core = File(CORE_ROOT).walkTopDown().filter { it.isFile && it.extension == "json" }
        val kits =
            File(KITS_ROOT)
                .walkTopDown()
                .filter { it.isFile && it.extension == "json" && it.parentFile.name == "dashboards" }
        return (core + kits).sortedBy { it.path }.toList()
    }
}
